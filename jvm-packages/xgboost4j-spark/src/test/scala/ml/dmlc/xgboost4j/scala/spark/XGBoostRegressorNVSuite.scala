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
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.{DoubleType, IntegerType, StructType}
import org.scalatest.FunSuite

class XGBoostRegressorNVSuite extends FunSuite with PerTest {

  private val NVRegressor = new XGBoostRegressor(Map(
    "silent" -> 1,
    "eta" -> 0.1f,
    "max_depth" -> 3,
    "objective" -> "reg:squarederror",
    "num_round" -> 50,
    "num_workers" -> 1,
    "timeout_request_workers" -> 60000L))

  override def afterEach(): Unit = {
    super.afterEach()
    NVDatasetData.regressionCleanUp()
  }

  test("test XGBoost-Spark XGBoostRegressor setFeaturesCols") {
    val gdfCols = Seq("gdfCol1", "gdfCol2")
    NVRegressor.setFeaturesCols(gdfCols)
    assert(NVRegressor.getFeaturesCols.contains("gdfCol1"))
    assert(NVRegressor.getFeaturesCols.contains("gdfCol2"))
    assert(NVRegressor.getFeaturesCols.length == 2)
  }

  test("test XGBoost-Spark XGBoostRegressor the overloaded 'fit' should work with NVDataset") {
    val csvSchema = new StructType()
      .add("b", DoubleType)
      .add("c", DoubleType)
      .add("d", DoubleType)
      .add("e", IntegerType)
    // group now is not supported
    val trainDataAsNVDS = new NVDataReader(ss).schema(csvSchema).csv(getPath("norank.train.csv"))
    NVRegressor.setFeaturesCols(csvSchema.fieldNames.filter(_ != "e"))
    NVRegressor.setLabelCol("e")
    val model = NVRegressor.fit(trainDataAsNVDS)
    val ret = model.predict(Vectors.dense(994.9573036, 317.483732878, 0.0313685555674))
    // Allow big range since we don't care the accuracy
    assert(0 < ret && ret < 20)
  }

  test("NV Regression XGBoost-Spark XGBoostRegressor output should match XGBoost4j") {
    val (trainFeaturesHandle, trainLabelsHandle) = NVDatasetData.regressionTrain
    assert(trainFeaturesHandle.nonEmpty)
    assert(trainFeaturesHandle.size == 3)
    assert(trainLabelsHandle.nonEmpty)
    assert(trainLabelsHandle.size == 1)

    val trainingDM = new DMatrix(trainFeaturesHandle)
    trainingDM.setCUDFInfo("label", trainLabelsHandle)

    val (testFeaturesHandle, _) = NVDatasetData.regressionTest
    assert(testFeaturesHandle.nonEmpty)
    assert(testFeaturesHandle.size == 3)

    val testDM = new DMatrix(testFeaturesHandle)

    val round = 149
    val paramMap = Map(
      "silent" -> 1,
      "eta" -> 0.1f,
      "max_depth" -> 3,
      "objective" -> "reg:squarederror",
      "num_round" -> 149,
      "num_workers" -> 1,
      "timeout_request_workers" -> 60000L,
      "tree_method" -> "gpu_hist",
      "max_bin" -> 16
      )

    val model1 = ScalaXGBoost.train(trainingDM, paramMap, round)
    val prediction1 = model1.predict(testDM)

    val trainingDF = NVDatasetData.getRegressionTrainNVDataset(ss)
    val featureCols = NVDatasetData.regressionFeatureCols
    val model2 = new XGBoostRegressor(paramMap ++ Array("num_round" -> round,
      "num_workers" -> 1))
      .setFeaturesCols(featureCols)
      .setLabelCol("e")
      .fit(trainingDF)

    val (testDF, testRows) = NVDatasetData.getRegressionTestNVDataset(ss)
    val prediction2 = model2.transform(testDF).
      collect().map(row => row.getAs[Double]("prediction"))

    assert(prediction1.indices.count { i =>
      math.abs(prediction1(i)(0) - prediction2(i)) > 0.01
    } < prediction1.length * 0.1)

    // check the equality of single instance prediction
    val prediction3 = model1.predict(testDM)(0)(0)
    val prediction4 = model2.predict(
      Vectors.dense(985.574005058, 320.223538037, 0.621236086198))
    assert(math.abs(prediction3 - prediction4) <= 0.01f)

    trainingDM.delete()
    testDM.delete()
  }

  test("NV Regression Set params in XGBoost and MLlib way should produce same model") {
    val trainingDF = NVDatasetData.getRegressionTrainNVDataset(ss)
    val featureCols = NVDatasetData.regressionFeatureCols
    val (testDF, testRows) = NVDatasetData.getRegressionTestNVDataset(ss)

    val paramMap = Map(
      "silent" -> 1,
      "eta" -> 0.1f,
      "max_depth" -> 3,
      "objective" -> "reg:squarederror",
      "num_round" -> 149,
      "num_workers" -> 1,
      "timeout_request_workers" -> 60000L,
      "features_cols" -> featureCols,
      "label_col" -> "e")

    // Set params in XGBoost way
    val model1 = new XGBoostRegressor(paramMap)
      .fit(trainingDF)
    // Set params in MLlib way
    val model2 = new XGBoostRegressor()
      .setSilent(1)
      .setEta(0.1)
      .setMaxDepth(3)
      .setObjective("reg:squarederror")
      .setNumRound(149)
      .setNumWorkers(1)
      .setTimeoutRequestWorkers(60000)
      .setFeaturesCols(featureCols)
      .setLabelCol("e")
      .fit(trainingDF)

    val prediction1 = model1.transform(testDF).select("prediction").collect()
    val prediction2 = model2.transform(testDF).select("prediction").collect()

    prediction1.zip(prediction2).foreach { case (Row(p1: Double), Row(p2: Double)) =>
      assert(math.abs(p1 - p2) <= 0.01f)
    }
  }

  test("NV regression test predictionLeaf") {
    val paramMap = Map(
      "silent" -> 1,
      "eta" -> 0.1f,
      "max_depth" -> 3,
      "objective" -> "reg:squarederror",
      "num_round" -> 149,
      "num_workers" -> 1,
      "timeout_request_workers" -> 60000L)

    val trainingDF = NVDatasetData.getRegressionTrainNVDataset(ss)
    val featureCols = NVDatasetData.regressionFeatureCols
    val (testDF, testRows) = NVDatasetData.getRegressionTestNVDataset(ss)

    val groundTruth = testRows
    val xgb = new XGBoostRegressor(paramMap)
    val model = xgb
      .setFeaturesCols(featureCols)
      .setLabelCol("e")
      .fit(trainingDF)
    model.setLeafPredictionCol("predictLeaf")
    val resultDF = model.transform(testDF)
    assert(resultDF.count === groundTruth)
    assert(resultDF.columns.contains("predictLeaf"))
  }

  test("NV regression test predictionLeaf with empty column name") {
    val paramMap = Map(
      "silent" -> 1,
      "eta" -> 0.1f,
      "max_depth" -> 3,
      "objective" -> "reg:squarederror",
      "num_round" -> 149,
      "num_workers" -> 1,
      "timeout_request_workers" -> 60000L)

    val trainingDF = NVDatasetData.getRegressionTrainNVDataset(ss)
    val featureCols = NVDatasetData.regressionFeatureCols
    val (testDF, testRows) = NVDatasetData.getRegressionTestNVDataset(ss)

    val xgb = new XGBoostRegressor(paramMap)
      .setFeaturesCols(featureCols)
      .setLabelCol("e")
    val model = xgb.fit(trainingDF)
    model.setLeafPredictionCol("")
    val resultDF = model.transform(testDF)
    assert(!resultDF.columns.contains("predictLeaf"))
  }

  test("NV regression test predictionContrib") {
    val paramMap = Map(
      "silent" -> 1,
      "eta" -> 0.1f,
      "max_depth" -> 3,
      "objective" -> "reg:squarederror",
      "num_round" -> 149,
      "num_workers" -> 1,
      "timeout_request_workers" -> 60000L)

    val trainingDF = NVDatasetData.getRegressionTrainNVDataset(ss)
    val featureCols = NVDatasetData.regressionFeatureCols
    val (testDF, testRows) = NVDatasetData.getRegressionTestNVDataset(ss)

    val groundTruth = testRows
    val xgb = new XGBoostRegressor(paramMap)
      .setFeaturesCols(featureCols)
      .setLabelCol("e")
    val model = xgb.fit(trainingDF)
    model.setContribPredictionCol("predictContrib")
    val resultDF = model.transform(testDF)
    assert(resultDF.count === groundTruth)
    assert(resultDF.columns.contains("predictContrib"))
  }

  test("NV Regression test predictionContrib with empty column name") {
    val paramMap = Map(
      "silent" -> 1,
      "eta" -> 0.1f,
      "max_depth" -> 3,
      "objective" -> "reg:squarederror",
      "num_round" -> 149,
      "num_workers" -> 1,
      "timeout_request_workers" -> 60000L)

    val trainingDF = NVDatasetData.getRegressionTrainNVDataset(ss)
    val featureCols = NVDatasetData.regressionFeatureCols
    val (testDF, testRows) = NVDatasetData.getRegressionTestNVDataset(ss)

    val xgb = new XGBoostRegressor(paramMap)
      .setFeaturesCols(featureCols)
      .setLabelCol("e")
    val model = xgb.fit(trainingDF)
    model.setContribPredictionCol("")
    val resultDF = model.transform(testDF)
    assert(!resultDF.columns.contains("predictContrib"))
  }

  test("NV Regression test predictionLeaf and predictionContrib") {
    val paramMap = Map(
      "silent" -> 1,
      "eta" -> 0.1f,
      "max_depth" -> 3,
      "objective" -> "reg:squarederror",
      "num_round" -> 149,
      "num_workers" -> 1,
      "timeout_request_workers" -> 60000L)

    val trainingDF = NVDatasetData.getRegressionTrainNVDataset(ss)
    val featureCols = NVDatasetData.regressionFeatureCols
    val (testDF, testRows) = NVDatasetData.getRegressionTestNVDataset(ss)

    val groundTruth = testRows
    val xgb = new XGBoostRegressor(paramMap)
      .setFeaturesCols(featureCols)
      .setLabelCol("e")
    val model = xgb.fit(trainingDF)
    model.setLeafPredictionCol("predictLeaf")
    model.setContribPredictionCol("predictContrib")
    val resultDF = model.transform(testDF)
    assert(resultDF.count === groundTruth)
    assert(resultDF.columns.contains("predictLeaf"))
    assert(resultDF.columns.contains("predictContrib"))
  }
}