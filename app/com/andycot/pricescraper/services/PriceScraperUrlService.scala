package com.andycot.pricescraper.services

import javax.inject.Inject

import com.andycot.pricescraper.models.{PriceScraperUrl, PriceScraperWebsite}
import com.andycot.pricescraper.utils.{MongoPriceScraperUrlCollection, PriceScraperUrlManager}
import org.mongodb.scala.MongoCollection

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
    * @param priceScraperWebsites
    * @return
    */
  def findPriceScraperUrlsAndParameters(priceScraperWebsites: Seq[PriceScraperWebsite]): Future[Seq[PriceScraperUrl]] =
    collection.find().toFuture().map(_.map { priceScraperUrl =>
      priceScraperUrl.copy(url = PriceScraperUrlManager.generateUrl(priceScraperUrl, priceScraperWebsites)(1))
    })
}
