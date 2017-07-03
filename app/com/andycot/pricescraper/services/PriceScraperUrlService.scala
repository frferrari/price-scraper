package com.andycot.pricescraper.services

import javax.inject.Inject

import com.andycot.pricescraper.models.{PriceScraperUrl, PriceScraperWebsite}
import com.andycot.pricescraper.utils.{MongoPriceScraperUrlCollection, PriceScraperUrlManager}
import org.mongodb.scala.{Completed, MongoCollection}
import org.mongodb.scala.model.Filters._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Created by Francois FERRARI on 10/06/2017
  */
class PriceScraperUrlService @Inject()(psuc: MongoPriceScraperUrlCollection) {

  val collection: MongoCollection[PriceScraperUrl] = psuc.getPriceScraperUrlCollection

  /**
    *
    * @return
    */
  def findPriceScraperUrls: Future[Seq[PriceScraperUrl]] = collection.find().toFuture()

  /**
    *
    * @param priceScraperWebsite
    * @return
    */
  def findPriceScraperUrlsAndParameters(priceScraperWebsite: PriceScraperWebsite): Future[Seq[PriceScraperUrl]] =
    collection
      .find(equal("website", priceScraperWebsite.website)).toFuture()
      .map {
        _.flatMap { priceScraperUrl =>
          PriceScraperUrlManager.generateAllUrls(priceScraperUrl, priceScraperWebsite, 1)
        }
      }

  /**
    * Inserts an Url in MongoDB
    *
    * @param priceScraperUrl The Url to insert
    * @return A future success or failure
    */
  def createOne(priceScraperUrl: PriceScraperUrl): Future[Completed] =
    collection.insertOne(priceScraperUrl).head()
}
