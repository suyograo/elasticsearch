/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.bucket;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.metrics.avg.Avg;
import org.elasticsearch.search.aggregations.metrics.stats.Stats;
import org.elasticsearch.search.aggregations.metrics.stats.extended.ExtendedStats;
import org.elasticsearch.search.aggregations.metrics.sum.Sum;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.aggregations.AggregationBuilders.*;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNull.notNullValue;

/**
 *
 */
public class DoubleTermsTests extends ElasticsearchIntegrationTest {

    private static final int NUM_DOCS = 5; // TODO: randomize the size?
    private static final String SINGLE_VALUED_FIELD_NAME = "d_value";
    private static final String MULTI_VALUED_FIELD_NAME = "d_values";

    @Override
    public Settings indexSettings() {
        return ImmutableSettings.builder()
                .put("index.number_of_shards", between(1, 5))
                .put("index.number_of_replicas", between(0, 1))
                .build();
    }

    @Before
    public void init() throws Exception {
        createIndex("idx");

        IndexRequestBuilder[] lowcardBuilders = new IndexRequestBuilder[NUM_DOCS];
        for (int i = 0; i < lowcardBuilders.length; i++) {
            lowcardBuilders[i] = client().prepareIndex("idx", "type").setSource(jsonBuilder()
                    .startObject()
                    .field(SINGLE_VALUED_FIELD_NAME, (double) i)
                    .startArray(MULTI_VALUED_FIELD_NAME).value((double)i).value(i + 1d).endArray()
                    .endObject());

        }
        indexRandom(randomBoolean(), lowcardBuilders);
        IndexRequestBuilder[] highCardBuilders = new IndexRequestBuilder[100]; // TODO: randomize the size?
        for (int i = 0; i < highCardBuilders.length; i++) {
            highCardBuilders[i] = client().prepareIndex("idx", "high_card_type").setSource(jsonBuilder()
                    .startObject()
                    .field(SINGLE_VALUED_FIELD_NAME, (double) i)
                    .startArray(MULTI_VALUED_FIELD_NAME).value((double)i).value(i + 1d).endArray()
                    .endObject());
        }
        indexRandom(true, highCardBuilders);

        createIndex("idx_unmapped");
        ensureSearchable();
    }

    private String key(Terms.Bucket bucket) {
        return randomBoolean() ? bucket.getKey() : bucket.getKeyAsText().string();
    }

    @Test
    // the main purpose of this test is to make sure we're not allocating 2GB of memory per shard
    public void sizeIsZero() {
        SearchResponse response = client().prepareSearch("idx").setTypes("high_card_type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .minDocCount(randomInt(1))
                        .size(0))
                .execute().actionGet();

        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(100));
    }

    @Test
    public void singleValueField() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double)i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1l));
        }
    }

    @Test
    public void singleValueField_WithMaxSize() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("high_card_type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .size(20)
                        .order(Terms.Order.term(true))) // we need to sort by terms cause we're checking the first 20 values
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(20));

        for (int i = 0; i < 20; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double) i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1l));
        }
    }

    @Test
    public void singleValueField_OrderedByTermAsc() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .order(Terms.Order.term(true)))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        int i = 0;
        for (Terms.Bucket bucket : terms.getBuckets()) {
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double)i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1l));
            i++;
        }
    }

    @Test
    public void singleValueField_OrderedByTermDesc() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .order(Terms.Order.term(false)))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        int i = 4;
        for (Terms.Bucket bucket : terms.getBuckets()) {
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double) i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1l));
            i--;
        }
    }

    @Test
    public void singleValuedField_WithSubAggregation() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .subAggregation(sum("sum").field(MULTI_VALUED_FIELD_NAME)))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double) i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1l));
            Sum sum = bucket.getAggregations().get("sum");
            assertThat(sum, notNullValue());
            assertThat((long) sum.getValue(), equalTo(i+i+1l));
        }
    }

    @Test
    public void singleValuedField_WithSubAggregation_Inherited() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .subAggregation(sum("sum")))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double) i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1l));
            Sum sum = bucket.getAggregations().get("sum");
            assertThat(sum, notNullValue());
            assertThat(sum.getValue(), equalTo((double) i));
        }
    }

    @Test
    public void singleValuedField_WithValueScript() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .script("_value + 1"))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (i + 1d));
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (i+1d)));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i+1));
            assertThat(bucket.getDocCount(), equalTo(1l));
        }
    }

    @Test
    public void multiValuedField() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(MULTI_VALUED_FIELD_NAME))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(6));

        for (int i = 0; i < 6; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double) i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            if (i == 0 || i == 5) {
                assertThat(bucket.getDocCount(), equalTo(1l));
            } else {
                assertThat(bucket.getDocCount(), equalTo(2l));
            }
        }
    }

    @Test
    public void multiValuedField_WithValueScript() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(MULTI_VALUED_FIELD_NAME)
                        .script("_value + 1"))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(6));

        for (int i = 0; i < 6; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (i + 1d));
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (i+1d)));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i+1));
            if (i == 0 || i == 5) {
                assertThat(bucket.getDocCount(), equalTo(1l));
            } else {
                assertThat(bucket.getDocCount(), equalTo(2l));
            }
        }
    }

    @Test
    public void multiValuedField_WithValueScript_NotUnique() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(MULTI_VALUED_FIELD_NAME)
                        .script("(long) _value / 1000 + 1"))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(1));

        Terms.Bucket bucket = terms.getBucketByKey("1.0");
        assertThat(bucket, notNullValue());
        assertThat(key(bucket), equalTo("1.0"));
        assertThat(bucket.getKeyAsNumber().intValue(), equalTo(1));
        assertThat(bucket.getDocCount(), equalTo(5l));
    }

    /*

    [1, 2]
    [2, 3]
    [3, 4]
    [4, 5]
    [5, 6]

    1 - count: 1 - sum: 1
    2 - count: 2 - sum: 4
    3 - count: 2 - sum: 6
    4 - count: 2 - sum: 8
    5 - count: 2 - sum: 10
    6 - count: 1 - sum: 6

    */

    @Test
    public void multiValuedField_WithValueScript_WithInheritedSubAggregator() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(MULTI_VALUED_FIELD_NAME)
                        .script("_value + 1")
                        .subAggregation(sum("sum")))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(6));

        for (int i = 0; i < 6; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (i + 1d));
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (i+1d)));
            assertThat(bucket.getKeyAsNumber().doubleValue(), equalTo(i+1d));
            final long count = i == 0 || i == 5 ? 1 : 2;
            double s = 0;
            for (int j = 0; j < NUM_DOCS; ++j) {
                if (i == j || i == j+1) {
                    s += j + 1;
                    s += j+1 + 1;
                }
            }
            assertThat(bucket.getDocCount(), equalTo(count));
            Sum sum = bucket.getAggregations().get("sum");
            assertThat(sum, notNullValue());
            assertThat(sum.getValue(), equalTo(s));
        }
    }

    @Test
    public void script_SingleValue() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .script("doc['" + MULTI_VALUED_FIELD_NAME + "'].value"))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double) i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1l));
        }
    }

    @Test
    public void script_SingleValue_WithSubAggregator_Inherited() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .subAggregation(sum("sum")))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double) i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1l));
            Sum sum = bucket.getAggregations().get("sum");
            assertThat(sum, notNullValue());
            assertThat(sum.getValue(), equalTo((double) i));
        }
    }

    @Test
    public void script_MultiValued() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .script("doc['" + MULTI_VALUED_FIELD_NAME + "'].values"))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(6));

        for (int i = 0; i < 6; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double) i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            if (i == 0 || i == 5) {
                assertThat(bucket.getDocCount(), equalTo(1l));
            } else {
                assertThat(bucket.getDocCount(), equalTo(2l));
            }
        }
    }

    @Test
    public void script_MultiValued_WithAggregatorInherited_NoExplicitType() throws Exception {

        // since no type is explicitly defined, es will assume all values returned by the script to be strings (bytes),
        // so the aggregation should fail, since the "sum" aggregation can only operation on numeric values.

        try {

            SearchResponse response = client().prepareSearch("idx").setTypes("type")
                    .addAggregation(terms("terms")
                            .script("doc['" + MULTI_VALUED_FIELD_NAME + "'].values")
                            .subAggregation(sum("sum")))
                    .execute().actionGet();


            fail("expected to fail as sub-aggregation sum requires a numeric value source context, but there is none");

        } catch (Exception e) {
            // expected
        }

    }

    @Test
    public void script_MultiValued_WithAggregatorInherited_WithExplicitType() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .script("doc['" + MULTI_VALUED_FIELD_NAME + "'].values")
                        .valueType(Terms.ValueType.DOUBLE)
                        .subAggregation(sum("sum")))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(6));

        for (int i = 0; i < 6; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i + ".0");
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i + ".0"));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            final long count = i == 0 || i == 5 ? 1 : 2;
            double s = 0;
            for (int j = 0; j < NUM_DOCS; ++j) {
                if (i == j || i == j+1) {
                    s += j;
                    s += j+1;
                }
            }
            assertThat(bucket.getDocCount(), equalTo(count));
            Sum sum = bucket.getAggregations().get("sum");
            assertThat(sum, notNullValue());
            assertThat(sum.getValue(), equalTo(s));
        }
    }

    @Test
    public void unmapped() throws Exception {
        SearchResponse response = client().prepareSearch("idx_unmapped").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .size(randomInt(5)))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(0));
    }

    @Test
    public void partiallyUnmapped() throws Exception {
        SearchResponse response = client().prepareSearch("idx_unmapped", "idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double) i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1l));
        }
    }

    @Test
    public void emptyAggregation() throws Exception {
        prepareCreate("empty_bucket_idx").addMapping("type", SINGLE_VALUED_FIELD_NAME, "type=integer").execute().actionGet();
        List<IndexRequestBuilder> builders = new ArrayList<IndexRequestBuilder>();
        for (int i = 0; i < 2; i++) {
            builders.add(client().prepareIndex("empty_bucket_idx", "type", ""+i).setSource(jsonBuilder()
                    .startObject()
                    .field(SINGLE_VALUED_FIELD_NAME, i*2)
                    .endObject()));
        }
        indexRandom(true, builders.toArray(new IndexRequestBuilder[builders.size()]));

        SearchResponse searchResponse = client().prepareSearch("empty_bucket_idx")
                .setQuery(matchAllQuery())
                .addAggregation(histogram("histo").field(SINGLE_VALUED_FIELD_NAME).interval(1l).minDocCount(0)
                        .subAggregation(terms("terms")))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(2l));
        Histogram histo = searchResponse.getAggregations().get("histo");
        assertThat(histo, Matchers.notNullValue());
        Histogram.Bucket bucket = histo.getBucketByKey(1l);
        assertThat(bucket, Matchers.notNullValue());

        Terms terms = bucket.getAggregations().get("terms");
        assertThat(terms, Matchers.notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().isEmpty(), is(true));
    }

    @Test
    public void singleValuedField_OrderedBySingleValueSubAggregationAsc() throws Exception {
        boolean asc = true;
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .order(Terms.Order.aggregation("avg_i", asc))
                        .subAggregation(avg("avg_i").field(SINGLE_VALUED_FIELD_NAME))
                ).execute().actionGet();


        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double)i));
            assertThat(bucket.getDocCount(), equalTo(1l));
            Avg avg = bucket.getAggregations().get("avg_i");
            assertThat(avg, notNullValue());
            assertThat(avg.getValue(), equalTo((double) i));
        }
    }

    @Test
    public void singleValuedField_OrderedByMissingSubAggregation() throws Exception {

        try {

            client().prepareSearch("idx").setTypes("type")
                    .addAggregation(terms("terms")
                            .field(SINGLE_VALUED_FIELD_NAME)
                            .order(Terms.Order.aggregation("avg_i", true))
                    ).execute().actionGet();

            fail("Expected search to fail when trying to sort terms aggregation by sug-aggregation that doesn't exist");

        } catch (ElasticsearchException e) {
            // expected
        }
    }

    @Test
    public void singleValuedField_OrderedByNonMetricsSubAggregation() throws Exception {

        try {

            client().prepareSearch("idx").setTypes("type")
                    .addAggregation(terms("terms")
                            .field(SINGLE_VALUED_FIELD_NAME)
                            .order(Terms.Order.aggregation("filter", true))
                            .subAggregation(filter("filter").filter(FilterBuilders.termFilter("foo", "bar")))
                    ).execute().actionGet();

            fail("Expected search to fail when trying to sort terms aggregation by sug-aggregation which is not of a metrics type");

        } catch (ElasticsearchException e) {
            // expected
        }
    }

    @Test
    public void singleValuedField_OrderedByMultiValuedSubAggregation_WithUknownMetric() throws Exception {

        try {

            client().prepareSearch("idx").setTypes("type")
                    .addAggregation(terms("terms")
                            .field(SINGLE_VALUED_FIELD_NAME)
                            .order(Terms.Order.aggregation("stats.foo", true))
                            .subAggregation(stats("stats").field(SINGLE_VALUED_FIELD_NAME))
                    ).execute().actionGet();

            fail("Expected search to fail when trying to sort terms aggregation by multi-valued sug-aggregation " +
                    "with an unknown specified metric to order by");

        } catch (ElasticsearchException e) {
            // expected
        }
    }

    @Test
    public void singleValuedField_OrderedByMultiValuedSubAggregation_WithoutMetric() throws Exception {

        try {

            client().prepareSearch("idx").setTypes("type")
                    .addAggregation(terms("terms")
                            .field(SINGLE_VALUED_FIELD_NAME)
                            .order(Terms.Order.aggregation("stats", true))
                            .subAggregation(stats("stats").field(SINGLE_VALUED_FIELD_NAME))
                    ).execute().actionGet();

            fail("Expected search to fail when trying to sort terms aggregation by multi-valued sug-aggregation " +
                    "where the metric name is not specified");

        } catch (ElasticsearchException e) {
            // expected
        }
    }

    @Test
    public void singleValuedField_OrderedBySingleValueSubAggregationDesc() throws Exception {
        boolean asc = false;
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .order(Terms.Order.aggregation("avg_i", asc))
                        .subAggregation(avg("avg_i").field(SINGLE_VALUED_FIELD_NAME))
                ).execute().actionGet();


        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 4; i >= 0; i--) {

            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double)i));
            assertThat(bucket.getDocCount(), equalTo(1l));

            Avg avg = bucket.getAggregations().get("avg_i");
            assertThat(avg, notNullValue());
            assertThat(avg.getValue(), equalTo((double) i));
        }

    }

    @Test
    public void singleValuedField_OrderedByMultiValueSubAggregationAsc() throws Exception {
        boolean asc = true;
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .order(Terms.Order.aggregation("stats.avg", asc))
                        .subAggregation(stats("stats").field(SINGLE_VALUED_FIELD_NAME))
                ).execute().actionGet();

        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double)i));
            assertThat(bucket.getDocCount(), equalTo(1l));

            Stats stats = bucket.getAggregations().get("stats");
            assertThat(stats, notNullValue());
            assertThat(stats.getMax(), equalTo((double) i));
        }

    }

    @Test
    public void singleValuedField_OrderedByMultiValueSubAggregationDesc() throws Exception {
        boolean asc = false;
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .order(Terms.Order.aggregation("stats.avg", asc))
                        .subAggregation(stats("stats").field(SINGLE_VALUED_FIELD_NAME))
                ).execute().actionGet();

        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 4; i >= 0; i--) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double)i));
            assertThat(bucket.getDocCount(), equalTo(1l));

            Stats stats = bucket.getAggregations().get("stats");
            assertThat(stats, notNullValue());
            assertThat(stats.getMax(), equalTo((double) i));
        }

    }

    @Test
    public void singleValuedField_OrderedByMultiValueExtendedStatsAsc() throws Exception {
        boolean asc = true;
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .order(Terms.Order.aggregation("stats.variance", asc))
                        .subAggregation(extendedStats("stats").field(SINGLE_VALUED_FIELD_NAME))
                ).execute().actionGet();

        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (double) i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (double)i));
            assertThat(bucket.getDocCount(), equalTo(1l));

            ExtendedStats stats = bucket.getAggregations().get("stats");
            assertThat(stats, notNullValue());
            assertThat(stats.getMax(), equalTo((double) i));
        }

    }


}