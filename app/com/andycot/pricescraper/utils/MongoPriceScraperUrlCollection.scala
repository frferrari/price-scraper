package com.andycot.pricescraper.utils

import javax.inject.Inject

import com.andycot.pricescraper.models.PriceScraperUrl
import com.andycot.pricescraper.services.{Mongo, MongoCodec}
import org.mongodb.scala.MongoCollection

/**
  * Created by Francois FERRARI on 26/06/2017
  */
class MongoPriceScraperUrlCollection @Inject() (mongo: Mongo) {
  val COLLECTION_NAME = "priceScraperUrls"

  def getPriceScraperUrlCollection: MongoCollection[PriceScraperUrl] = mongo.db.getCollection[PriceScraperUrl](COLLECTION_NAME)
    .withCodecRegistry(MongoCodec.getCodecRegistry)
}
