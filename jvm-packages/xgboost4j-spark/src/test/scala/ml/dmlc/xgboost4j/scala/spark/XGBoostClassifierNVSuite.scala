/*
 Copyright (c) 2014 by Contributors

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package ml.dmlc.xgboost4j.scala.spark

import ml.dmlc.xgboost4j.scala.spark.nvidia.NVDataReader
import ml.dmlc.xgboost4j.scala.{DMatrix, XGBoost => ScalaXGBoost}
import org.apache.spark.ml.linalg.{DenseVector, Vectors}
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.{FloatType, IntegerType, StructType}
import org.scalatest.FunSuite

class XGBoostClassifierNVSuite extends FunSuite with PerTest {

  override def afterEach(): Unit = {
    super.afterEach()
    NVDatasetData.classifierCleanUp()
  }

  test("test XGBoost-Spark XGBoostClassifier setFeaturesCols") {
    val nvClassifier = new XGBoostClassifier(Map("objective" -> "multi:softprob"))
      .setFeaturesCols(Seq("gdfCol1", "gdfCol2"))
    assert(nvClassifier.getFeaturesCols.contains("gdfCol1"))
    assert(nvClassifier.getFeaturesCols.contains("gdfCol2"))
    assert(nvClassifier.getFeaturesCols.length == 2)
  }

  test("test XGBoost-Spark XGBoostClassifier the overloaded 'fit' should work with NVDataset") {
    val paramMap = Map(
      "silent" -> 1,
      "eta" -> 0.2f,
      "max_depth" -> 2,
      "objective" -> "multi:softprob",
      "num_round" -> 30,
      "num_workers" -> 1,
      "timeout_request_workers" -> 60000L)
    val csvSchema = new StructType()
      .add("b", FloatType)
      .add("c", FloatType)
      .add("d", FloatType)
      .add("e", IntegerType)
    val trainDataAsNVDS = new NVDataReader(ss).schema(csvSchema).csv(getPath("norank.train.csv"))
    val nvClassifier = new XGBoostClassifier(paramMap)
      .setFeaturesCols(csvSchema.fieldNames.filter(_ != "e"))
      .setLabelCol("e")

    // num_class is required for multiple classification
    assertThrows[IllegalArgumentException](nvClassifier.fit(trainDataAsNVDS))
    // Invalid num_class
    nvClassifier.setNumClass(-1)
    assertThrows[IllegalArgumentException](nvClassifier.fit(trainDataAsNVDS))
    // num_class can be verified by automatic detection
    nvClassifier.setNumClass(50)
    assertThrows[IllegalArgumentException](nvClassifier.fit(trainDataAsNVDS))

    // Per the training data, num classes is 21
    nvClassifier.setNumClass(21)

    // Train without eval set(s)
    val model = nvClassifier.fit(trainDataAsNVDS)
    val ret = model.predict(Vectors.dense(994.9573036, 317.483732878, 0.0313685555674))
    // Allow big range since we don't care the accuracy
    assert(0 < ret && ret < 20)

    // Train with eval set(s)
    val evalDataAsNVDS = new NVDataReader(ss).schema(csvSchema).csv(getPath("norank.eval.csv"))
    // 1) Set via xgboost ML API
    nvClassifier.setNvEvalSets(Map("test" -> evalDataAsNVDS))
    val model2 = nvClassifier.fit(trainDataAsNVDS)
    val ret2 = model2.predict(Vectors.dense(994.9573036, 317.483732878, 0.0313685555674))
    // Allow big range since we don't care the accuracy
    assert(0 < ret2 && ret2 < 20)
    // 2) Set via param map
    val model3 = new XGBoostClassifier(paramMap ++ Array(
      "num_class" -> 21,
      "eval_sets" -> Map("test" -> evalDataAsNVDS)))
      .setFeaturesCols(csvSchema.fieldNames.filter(_ != "e"))
      .setLabelCol("e")
      .fit(trainDataAsNVDS)
    val ret3 = model3.predict(Vectors.dense(994.9573036, 317.483732878, 0.0313685555674))
    assert(0 < ret3 && ret3 < 20)
    assert(ret2 === ret3)
  }

  test("NV Classifier XGBoost-Spark XGBoostClassifier output should match XGBoost4j") {
    val (trainFeaturesHandle, trainLabelsHandle) = NVDatasetData.classifierTrain
    assert(trainFeaturesHandle.nonEmpty)
    assert(trainFeaturesHandle.size == 4)
    assert(trainLabelsHandle.nonEmpty)
    assert(trainLabelsHandle.size == 1)

    val trainingDM = new DMatrix(trainFeaturesHandle)
    trainingDM.setCUDFInfo("label", trainLabelsHandle)

    val (testFeaturesHandle, _) = NVDatasetData.classifierTest
    assert(testFeaturesHandle.nonEmpty)
    assert(testFeaturesHandle.size == 4)

    val testDM = new DMatrix(testFeaturesHandle)

    val round = 100
    val paramMap = Map("eta" -> 0.1f,
      "max_depth" -> 2,
      "objective" -> "multi:softprob",
      "num_class" -> 3,
      "num_round" -> 100,
      "num_workers" -> 1,
      "tree_method" -> "gpu_hist",
      "max_bin" -> 16)

    val model1 = ScalaXGBoost.train(trainingDM, paramMap, round)
    val prediction1 = model1.predict(testDM)

    val trainingDF = NVDatasetData.getClassifierTrainNVDataset(ss)
    val featureCols = NVDatasetData.classifierFeatureCols
    val model2 = new XGBoostClassifier(paramMap ++ Array("num_round" -> round,
      "num_workers" -> 1))
      .setFeaturesCols(featureCols)
      .setLabelCol("classIndex")
      .fit(trainingDF)

    val (testDF, testRows) = NVDatasetData.getClassifierTestNVDataset(ss)
    val prediction2 = model2.transform(testDF)
      .collect().map(row => row.getAs[DenseVector]("probability"))

    // the vector length in probability column is 2 since we have to fit to the evaluator in Spark
    assert(testRows === prediction2.size)
    for (i <- prediction1.indices) {
      assert(prediction1(i).length === prediction2(i).values.length)
      for (j <- prediction1(i).indices) {
        assert(prediction1(i)(j) === prediction2(i)(j))
      }
    }

    val prediction3 = model1.predict(testDM, outPutMargin = true)
    val prediction4 = model2.transform(testDF).
      collect().map(row => row.getAs[DenseVector]("rawPrediction"))

    // the vector length in rawPrediction column is 2 since we have to fit to the evaluator in Spark
    assert(testRows === prediction4.size)
    for (i <- prediction3.indices) {
      assert(prediction3(i).length === prediction4(i).values.length)
      for (j <- prediction3(i).indices) {
        assert(prediction3(i)(j) === prediction4(i)(j))
      }
    }

    trainingDM.delete()
    testDM.delete()
  }

  test("NV Classifier Set params in XGBoost and MLlib way should produce same model") {
    val trainingDF = NVDatasetData.getClassifierTrainNVDataset(ss)
    val featureCols = NVDatasetData.classifierFeatureCols
    val (testDF, testRows) = NVDatasetData.getClassifierTestNVDataset(ss)

    val round = 100
    val paramMap = Map("eta" -> 0.1f,
      "max_depth" -> 2,
      "objective" -> "multi:softprob",
      "num_class" -> 3,
      "num_round" -> 100,
      "num_workers" -> 1,
      "tree_method" -> "gpu_hist",
      "max_bin" -> 16,
      "features_cols" -> featureCols,
      "label_col" -> "classIndex")

    // Set params in XGBoost way
    val model1 = new XGBoostClassifier(paramMap)
      .fit(trainingDF)

    // Set params in MLlib way
    val model2 = new XGBoostClassifier()
      .setEta(0.1f)
      .setMaxDepth(2)
      .setObjective("multi:softprob")
      .setNumRound(round)
      .setNumClass(3)
      .setNumWorkers(1)
      .setMaxBins(16)
      .setTreeMethod("gpu_hist")
      .setFeaturesCols(featureCols)
      .setLabelCol("classIndex")
      .fit(trainingDF)

    val prediction1 = model1.transform(testDF).select("prediction").collect()
    val prediction2 = model2.transform(testDF).select("prediction").collect()

    prediction1.zip(prediction2).foreach { case (Row(p1: Double), Row(p2: Double)) =>
      assert(p1 === p2)
    }
  }

  test("NV Classifier test schema of XGBoostClassificationModel") {
    val paramMap = Map("eta" -> 0.1f,
      "max_depth" -> 2,
      "objective" -> "multi:softprob",
      "num_class" -> 3,
      "num_round" -> 100,
      "num_workers" -> 1,
      "tree_method" -> "gpu_hist",
      "max_bin" -> 16)

    val trainingDF = NVDatasetData.getClassifierTrainNVDataset(ss)
    val featureCols = NVDatasetData.classifierFeatureCols
    val (testDF, testRows) = NVDatasetData.getClassifierTestNVDataset(ss)

    val model = new XGBoostClassifier(paramMap)
      .setFeaturesCols(featureCols)
      .setLabelCol("classIndex")
      .fit(trainingDF)

    model.setRawPredictionCol("raw_prediction")
      .setProbabilityCol("probability_prediction")
      .setPredictionCol("final_prediction")
    var predictionDF = model.transform(testDF)
    assert(predictionDF.columns.contains("sepal length"))
    assert(predictionDF.columns.contains("classIndex"))
    assert(predictionDF.columns.contains("raw_prediction"))
    assert(predictionDF.columns.contains("probability_prediction"))
    assert(predictionDF.columns.contains("final_prediction"))
    model.setRawPredictionCol("").setPredictionCol("final_prediction")
    predictionDF = model.transform(testDF)
    assert(predictionDF.columns.contains("raw_prediction") === false)
    assert(predictionDF.columns.contains("final_prediction"))
    model.setRawPredictionCol("raw_prediction").setPredictionCol("")
    predictionDF = model.transform(testDF)
    assert(predictionDF.columns.contains("raw_prediction"))
    assert(predictionDF.columns.contains("final_prediction") === false)

    assert(model.summary.trainObjectiveHistory.length === 100)
    assert(model.summary.validationObjectiveHistory.isEmpty)
  }

  test("NV Classifier test predictionLeaf") {
    val paramMap = Map("eta" -> 0.1f,
      "max_depth" -> 2,
      "objective" -> "multi:softprob",
      "num_class" -> 3,
      "num_round" -> 100,
      "num_workers" -> 1,
      "tree_method" -> "gpu_hist",
      "max_bin" -> 16)

    val trainingDF = NVDatasetData.getClassifierTrainNVDataset(ss)
    val featureCols = NVDatasetData.classifierFeatureCols
    val (testDF, testRows) = NVDatasetData.getClassifierTestNVDataset(ss)

    val groundTruth = testRows
    val xgb = new XGBoostClassifier(paramMap)
      .setFeaturesCols(featureCols)
      .setLabelCol("classIndex")
    val model = xgb.fit(trainingDF)
    model.setLeafPredictionCol("predictLeaf")
    val resultDF = model.transform(testDF)
    assert(resultDF.count == groundTruth)
    assert(resultDF.columns.contains("predictLeaf"))
  }

  test("NV Classifier test predictionLeaf with empty column name") {
    val paramMap = Map("eta" -> 0.1f,
      "max_depth" -> 2,
      "objective" -> "multi:softprob",
      "num_class" -> 3,
      "num_round" -> 100,
      "num_workers" -> 1,
      "tree_method" -> "gpu_hist",
      "max_bin" -> 16)

    val trainingDF = NVDatasetData.getClassifierTrainNVDataset(ss)
    val featureCols = NVDatasetData.classifierFeatureCols
    val (testDF, testRows) = NVDatasetData.getClassifierTestNVDataset(ss)

    val xgb = new XGBoostClassifier(paramMap)
      .setFeaturesCols(featureCols)
      .setLabelCol("classIndex")
    val model = xgb.fit(trainingDF)
    model.setLeafPredictionCol("")
    val resultDF = model.transform(testDF)
    assert(!resultDF.columns.contains("predictLeaf"))
  }

  test("NV Classifier test predictionContrib") {
    val paramMap = Map("eta" -> 0.1f,
      "max_depth" -> 2,
      "objective" -> "multi:softprob",
      "num_class" -> 3,
      "num_round" -> 100,
      "num_workers" -> 1,
      "tree_method" -> "gpu_hist",
      "max_bin" -> 16)

    val trainingDF = NVDatasetData.getClassifierTrainNVDataset(ss)
    val featureCols = NVDatasetData.classifierFeatureCols
    val (testDF, testRows) = NVDatasetData.getClassifierTestNVDataset(ss)

    val groundTruth = testRows
    val xgb = new XGBoostClassifier(paramMap)
      .setFeaturesCols(featureCols)
      .setLabelCol("classIndex")
    val model = xgb.fit(trainingDF)
    model.setContribPredictionCol("predictContrib")
    val resultDF = model.transform(testDF)
    assert(resultDF.count == groundTruth)
    assert(resultDF.columns.contains("predictContrib"))
  }

  test("NV Classifier test predictionContrib with empty column name") {
    val paramMap = Map("eta" -> 0.1f,
      "max_depth" -> 2,
      "objective" -> "multi:softprob",
      "num_class" -> 3,
      "num_round" -> 100,
      "num_workers" -> 1,
      "tree_method" -> "gpu_hist",
      "max_bin" -> 16)

    val trainingDF = NVDatasetData.getClassifierTrainNVDataset(ss)
    val featureCols = NVDatasetData.classifierFeatureCols
    val (testDF, testRows) = NVDatasetData.getClassifierTestNVDataset(ss)

    val xgb = new XGBoostClassifier(paramMap)
      .setFeaturesCols(featureCols)
      .setLabelCol("classIndex")
    val model = xgb.fit(trainingDF)
    model.setContribPredictionCol("")
    val resultDF = model.transform(testDF)
    assert(!resultDF.columns.contains("predictContrib"))
  }

  test("NV Classifier test predictionLeaf and predictionContrib") {
    val paramMap = Map("eta" -> 0.1f,
      "max_depth" -> 2,
      "objective" -> "multi:softprob",
      "num_class" -> 3,
      "num_round" -> 100,
      "num_workers" -> 1,
      "tree_method" -> "gpu_hist",
      "max_bin" -> 16)

    val trainingDF = NVDatasetData.getClassifierTrainNVDataset(ss)
    val featureCols = NVDatasetData.classifierFeatureCols
    val (testDF, testRows) = NVDatasetData.getClassifierTestNVDataset(ss)

    val groundTruth = testRows
    val xgb = new XGBoostClassifier(paramMap)
      .setFeaturesCols(featureCols)
      .setLabelCol("classIndex")
    val model = xgb.fit(trainingDF)
    model.setLeafPredictionCol("predictLeaf")
    model.setContribPredictionCol("predictContrib")
    val resultDF = model.transform(testDF)
    assert(resultDF.count == groundTruth)
    assert(resultDF.columns.contains("predictLeaf"))
    assert(resultDF.columns.contains("predictContrib"))
  }
}
