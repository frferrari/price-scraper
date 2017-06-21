package com.andycot.pricescraper.services

import com.andycot.pricescraper.models.PriceScraperWebsite
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.{Completed, MongoClient, MongoDatabase, _}

import scala.concurrent.Future

/**
  * Created by Francois FERRARI on 10/06/2017
  */
class PriceScraperWebsiteService {
  val mongoClient: MongoClient = MongoClient()
  val database: MongoDatabase = mongoClient.getDatabase("andycot")

  /*
   *
   */
  val collection: MongoCollection[PriceScraperWebsite] = database
    .getCollection[PriceScraperWebsite]("priceScraperWebsites")
    .withCodecRegistry(MongoCodec.getCodecRegistry)

  /**
    * Create a website
    *
    * @return
    */
  def createOne(priceCrawlerWebsite: PriceScraperWebsite): Future[Completed] =
    collection.insertOne(priceCrawlerWebsite).head()

  /**
    * List all the websites
    *
    * @return
    */
  def findAll: Future[Seq[PriceScraperWebsite]] = collection.find.toFuture()

  /**
    *
    * @param name
    * @return
    */
  def find(name: String): Future[PriceScraperWebsite] =
    collection.find(equal("name", name)).head()
}
