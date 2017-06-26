package com.andycot.pricescraper.services

import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.{MongoClient, MongoDatabase}
import play.Logger
import play.api.Configuration
import play.api.inject.ApplicationLifecycle

import scala.concurrent.Future

/**
  * Created by Francois FERRARI on 26/06/2017
  */
@Singleton
class Mongo @Inject()(applicationLifecycle: ApplicationLifecycle, configuration: Configuration) {

  val mongoDbUri = configuration.getString("mongo.db.uri").getOrElse("mongodb://localhost:27017")
  val client: MongoClient = MongoClient(mongoDbUri)

  private val dbName: String = configuration.getString("mongo.db.name").getOrElse("default")

  val db: MongoDatabase = client.getDatabase(dbName)

  Logger.info(s"Opening mongodb $mongoDbUri for db $dbName")

  applicationLifecycle.addStopHook(() => {
    Logger.warn("Closing Mongo connection")
    Future.successful(client.close())
  })
}