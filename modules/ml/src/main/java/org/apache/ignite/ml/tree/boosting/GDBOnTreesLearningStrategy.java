/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.ml.tree.boosting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.ignite.ml.Model;
import org.apache.ignite.ml.composition.ModelsComposition;
import org.apache.ignite.ml.composition.boosting.GDBLearningStrategy;
import org.apache.ignite.ml.composition.predictionsaggregator.WeightedPredictionsAggregator;
import org.apache.ignite.ml.dataset.Dataset;
import org.apache.ignite.ml.dataset.DatasetBuilder;
import org.apache.ignite.ml.dataset.primitive.builder.context.EmptyContextBuilder;
import org.apache.ignite.ml.dataset.primitive.context.EmptyContext;
import org.apache.ignite.ml.environment.logging.MLLogger;
import org.apache.ignite.ml.math.functions.IgniteBiFunction;
import org.apache.ignite.ml.math.primitives.vector.Vector;
import org.apache.ignite.ml.math.primitives.vector.VectorUtils;
import org.apache.ignite.ml.trainers.DatasetTrainer;
import org.apache.ignite.ml.tree.DecisionTree;
import org.apache.ignite.ml.tree.data.DecisionTreeData;
import org.apache.ignite.ml.tree.data.DecisionTreeDataBuilder;

/**
 * Gradient boosting on trees specific learning strategy reusing learning dataset with index between
 * several learning iterations.
 */
public class GDBOnTreesLearningStrategy  extends GDBLearningStrategy {
    private boolean useIndex;

    /**
     * Create an instance of learning strategy.
     *
     * @param useIndex Use index.
     */
    public GDBOnTreesLearningStrategy(boolean useIndex) {
        this.useIndex = useIndex;
    }

    /** {@inheritDoc} */
    @Override public <K, V> List<Model<Vector, Double>> learnModels(DatasetBuilder<K, V> datasetBuilder,
        IgniteBiFunction<K, V, Vector> featureExtractor, IgniteBiFunction<K, V, Double> lbExtractor) {

        DatasetTrainer<? extends Model<Vector, Double>, Double> trainer = baseMdlTrainerBuilder.get();
        assert trainer instanceof DecisionTree;
        DecisionTree decisionTreeTrainer = (DecisionTree) trainer;

        List<Model<Vector, Double>> models = new ArrayList<>();
        try (Dataset<EmptyContext, DecisionTreeData> dataset = datasetBuilder.build(
            new EmptyContextBuilder<>(),
            new DecisionTreeDataBuilder<>(featureExtractor, lbExtractor, useIndex)
        )) {
            for (int i = 0; i < cntOfIterations; i++) {
                double[] weights = Arrays.copyOf(compositionWeights, i);
                WeightedPredictionsAggregator aggregator = new WeightedPredictionsAggregator(weights, meanLabelValue);
                Model<Vector, Double> currComposition = new ModelsComposition(models, aggregator);

                dataset.compute(part -> {
                    if(part.getCopyOfOriginalLabels() == null)
                        part.setCopyOfOriginalLabels(Arrays.copyOf(part.getLabels(), part.getLabels().length));

                    for(int j = 0; j < part.getLabels().length; j++) {
                        double mdlAnswer = currComposition.apply(VectorUtils.of(part.getFeatures()[j]));
                        double originalLbVal = externalLbToInternalMapping.apply(part.getCopyOfOriginalLabels()[j]);
                        part.getLabels()[j] = -lossGradient.apply(sampleSize, originalLbVal, mdlAnswer);
                    }
                });

                long startTs = System.currentTimeMillis();
                models.add(decisionTreeTrainer.fit(dataset));
                double learningTime = (double)(System.currentTimeMillis() - startTs) / 1000.0;
                environment.logger(getClass()).log(MLLogger.VerboseLevel.LOW, "One model training time was %.2fs", learningTime);
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }

        return models;
    }
}
