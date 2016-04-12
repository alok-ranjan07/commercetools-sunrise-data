package com.commercetools.dataimport.products;

import com.commercetools.dataimport.commercetools.CommercetoolsPayloadFileConfig;
import com.commercetools.dataimport.commercetools.CommercetoolsJobConfiguration;
import com.commercetools.sdk.jvm.spring.batch.item.ItemReaderFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.neovisionaries.i18n.CountryCode;
import io.sphere.sdk.categories.Category;
import io.sphere.sdk.categories.CategoryTree;
import io.sphere.sdk.categories.queries.CategoryQuery;
import io.sphere.sdk.client.SphereClientUtils;
import io.sphere.sdk.customergroups.CustomerGroup;
import io.sphere.sdk.customergroups.commands.CustomerGroupCreateCommand;
import io.sphere.sdk.customergroups.queries.CustomerGroupQuery;
import io.sphere.sdk.models.Versioned;
import io.sphere.sdk.products.Product;
import io.sphere.sdk.products.ProductDraft;
import io.sphere.sdk.products.attributes.AttributeDraft;
import io.sphere.sdk.products.commands.ProductCreateCommand;
import io.sphere.sdk.products.commands.ProductUpdateCommand;
import io.sphere.sdk.products.commands.updateactions.Publish;
import io.sphere.sdk.products.commands.updateactions.Unpublish;
import io.sphere.sdk.products.queries.ProductQuery;
import io.sphere.sdk.producttypes.ProductType;
import io.sphere.sdk.producttypes.queries.ProductTypeQuery;
import io.sphere.sdk.taxcategories.TaxCategory;
import io.sphere.sdk.taxcategories.TaxCategoryDraft;
import io.sphere.sdk.taxcategories.TaxRate;
import io.sphere.sdk.taxcategories.commands.TaxCategoryCreateCommand;
import io.sphere.sdk.taxcategories.queries.TaxCategoryQuery;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.net.MalformedURLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.sphere.sdk.client.SphereClientUtils.blockingWait;
import static io.sphere.sdk.queries.QueryExecutionUtils.queryAll;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.isEmpty;

@Configuration
@EnableBatchProcessing
@EnableAutoConfiguration
public class ProductsImportJobConfiguration extends CommercetoolsJobConfiguration {
    static final String b2bCustomerGroupStepContextKey = "b2bCustomerGroupId";
    static final String taxCategoryKey = "taxCategory";
    static final String categoryTreeKey = "categoryTreeKey";
    static final String productTypesStepContextKey = "productTypes";

    @Autowired
    private Resource productsCsvResource;

    @Value("${productsImportStep.maxProducts:1000}")
    private int maxProducts;

    @Value("${productsImportStep.chunkSize:20}")
    private int productsImportStepChunkSize;

    @Bean
    public Job importProducts(final Step getOrCreateCustomerGroup,
                              final Step getOrCreateTaxCategoryStep,
                              final Step productsImportStep,
                              final Step getProductTypesStep,
                              final Step publishProductsStep) {
        return jobBuilderFactory.get("productsImportJob")
                .start(getOrCreateCustomerGroup)
                .next(getOrCreateTaxCategoryStep)
                .next(getProductTypesStep)
                .next(productsImportStep)
                .next(publishProductsStep)
                .build();
    }

    @Bean
    public Step getProductTypesStep() {
        final ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
        listener.setKeys(new String[]{productTypesStepContextKey});
        return stepBuilderFactory.get("getProductTypesStep")
                .tasklet(saveProductTypeTasklet())
                .listener(listener)
                .build();
    }

    @Bean
    public Step publishProductsStep() {
        return stepBuilderFactory.get("publishProductsStep")
                .<Product, Product>chunk(20)
                .reader(ItemReaderFactory.sortedByIdQueryReader(sphereClient, ProductQuery.of()))
                .writer(productPublishWriter())
                .build();
    }

    private ItemWriter<Versioned<Product>> productPublishWriter() {
        return items -> {
            final List<CompletionStage<Product>> completionStages = items.stream()
                    .map(item -> sphereClient.execute(ProductUpdateCommand.of(item, Publish.of())))
                    .collect(toList());
            completionStages.forEach(stage -> SphereClientUtils.blockingWait(stage, 60, TimeUnit.SECONDS));
        };
    }

    private Tasklet saveProductTypeTasklet() {
        return (contribution, chunkContext) -> {
            final List<ProductType> productTypes = blockingWait(queryAll(sphereClient, ProductTypeQuery.of()), 30, TimeUnit.SECONDS);
            chunkContext.getStepContext().getStepExecution().getExecutionContext().put(productTypesStepContextKey, productTypes);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step getOrCreateTaxCategoryStep() {
        final ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
        listener.setKeys(new String[]{taxCategoryKey, categoryTreeKey});
        return stepBuilderFactory.get("getOrCreateTaxCategoryStep")
                .tasklet(saveTaxCategoryTasklet())
                .listener(listener)
                .build();
    }

    @Bean
    public Step getOrCreateCustomerGroup() {
        final ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
        listener.setKeys(new String[]{b2bCustomerGroupStepContextKey});
        return stepBuilderFactory.get("getOrCreateCustomerGroupStep")
                .tasklet(saveCustomerGroupTasklet())
                .listener(listener)
                .build();
    }

    private Tasklet saveTaxCategoryTasklet() {
        return (contribution, chunkContext) -> {
            final String name = "standard";
            final TaxCategory taxCategory =
                    sphereClient.executeBlocking(TaxCategoryQuery.of().byName(name)).head()
                    .orElseGet(() -> {
                        final TaxCategoryDraft body = TaxCategoryDraft.of(name, asList(
                                TaxRate.of(name, 0.19, true, CountryCode.DE),
                                TaxRate.of(name, 0.08, true, CountryCode.CH),
                                TaxRate.of(name, 0.21, true, CountryCode.CZ),
                                TaxRate.of(name, 0.22, true, CountryCode.IT),
                                TaxRate.of(name, 0.20, true, CountryCode.AU)
                        ));
                        return sphereClient.executeBlocking(TaxCategoryCreateCommand.of(body));
                    });


            final List<Category> categories = blockingWait(queryAll(sphereClient, CategoryQuery.of()), 3, TimeUnit.MINUTES);
            final CategoryTree categoryTree = CategoryTree.of(categories);

            final ExecutionContext executionContext = chunkContext.getStepContext()
                    .getStepExecution()
                    .getExecutionContext();
            executionContext.put(taxCategoryKey, taxCategory);
            executionContext.put(categoryTreeKey, categoryTree);
            return RepeatStatus.FINISHED;
        };
    }

    private Tasklet saveCustomerGroupTasklet() {
        return (contribution, chunkContext) -> {
            final String customerGroupName = "b2b";
            final CustomerGroup customerGroup =
                    sphereClient.executeBlocking(CustomerGroupQuery.of().byName(customerGroupName)).head()
                    .orElseGet(() -> sphereClient.executeBlocking(CustomerGroupCreateCommand.of(customerGroupName)));
            final ExecutionContext executionContext = chunkContext.getStepContext()
                    .getStepExecution()
                    .getExecutionContext();
            executionContext.put(b2bCustomerGroupStepContextKey, customerGroup.getId());
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step productsImportStep() {
        final StepBuilder stepBuilder = stepBuilderFactory.get("productsImportStep");
        return stepBuilder
                .<ProductDraft, ProductDraft>chunk(productsImportStepChunkSize)
                .reader(productsReader())
                .processor(productsProcessor())
                .writer(productsWriter())
                .build();
    }

    @Bean
    protected ItemProcessor<ProductDraft, ProductDraft> productsProcessor() {
        return new ItemProcessor<ProductDraft, ProductDraft>() {
            @Override
            public ProductDraft process(final ProductDraft item) throws Exception {
                return isUseful(item) ? item : null;//filter out products without useful name
            }

            private boolean isUseful(final ProductDraft item) {
                return !isEmpty(item.getName().get(Locale.ENGLISH)) && !item.getName().find(Locale.GERMAN).orElse("").startsWith("#max");
            }
        };
    }

    @Bean
    protected ItemReader<ProductDraft> productsReader() {
        return new ProductDraftReader(productsCsvResource, maxProducts);
    }

    @Bean
    protected ItemWriter<ProductDraft> productsWriter() {
        return new ItemWriter<ProductDraft>() {
            @Override
            public void write(final List<? extends ProductDraft> items) throws Exception {
                items.stream()
                        //!!!TODO some products are filtered out since the product type is incomplete
                        .filter(item -> {
                            final List<AttributeDraft> attributes = item.getMasterVariant().getAttributes();
                            return !attributes.stream().anyMatch(a -> a.getName().equals("designer") && a.getValue().equals(new TextNode("juliat")));
                        })
                        .map(item -> sphereClient.execute(ProductCreateCommand.of(item)))
                        .collect(toList())
                        .forEach(stage -> blockingWait(stage, 30, TimeUnit.SECONDS));
            }
        };
    }
}
