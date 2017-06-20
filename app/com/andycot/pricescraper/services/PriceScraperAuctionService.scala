package com.andycot.pricescraper.services

import com.andycot.pricescraper.models.{PriceScraperAuction, PriceScraperAuction$}
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.{Completed, MongoClient, MongoDatabase, _}

import scala.concurrent.Future

/**
  * Created by Francois FERRARI on 10/06/2017
  */
class PriceScraperAuctionService {
  val mongoClient: MongoClient = MongoClient()
  val database: MongoDatabase = mongoClient.getDatabase("andycot")

  /*
   *
   */
  val collection: MongoCollection[PriceScraperAuction] = database
    .getCollection[PriceScraperAuction]("priceCrawlerAuctions")
    .withCodecRegistry(MongoCodec.getCodecRegistry)

  // TODO add an index --- collection.createIndex(Document("auctionId" -> 1, "unique" -> true))

  /**
    *
    * @return
    */
  def createOne(priceCrawlerAuction: PriceScraperAuction): Future[Completed] =
    collection.insertOne(priceCrawlerAuction).head()

  def createMany(priceCrawlerAuctions: Seq[PriceScraperAuction]): Future[Completed] =
    collection.insertMany(priceCrawlerAuctions).head()

  /**
    *
    * @param priceScraperAuctions
    * @return
    */
  def findMany(priceScraperAuctions: Seq[PriceScraperAuction]): Future[Seq[PriceScraperAuction]] = {
    val auctionIds = priceScraperAuctions.map(_.auctionId)

    collection.find(in("auctionId", auctionIds: _*)).toFuture()
  }
}
