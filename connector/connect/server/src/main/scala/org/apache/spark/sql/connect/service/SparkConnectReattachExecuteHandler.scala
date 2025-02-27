/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.connect.service

import io.grpc.stub.StreamObserver

import org.apache.spark.SparkSQLException
import org.apache.spark.connect.proto
import org.apache.spark.internal.Logging
import org.apache.spark.sql.connect.execution.ExecuteGrpcResponseSender

class SparkConnectReattachExecuteHandler(
    responseObserver: StreamObserver[proto.ExecutePlanResponse])
    extends Logging {

  def handle(v: proto.ReattachExecuteRequest): Unit = {
    val sessionHolder = SparkConnectService
      .getIsolatedSession(v.getUserContext.getUserId, v.getSessionId)
    val executeHolder = sessionHolder.executeHolder(v.getOperationId).getOrElse {
      logDebug(s"Reattach operation not found: ${v.getOperationId}")
      throw new SparkSQLException(
        errorClass = "INVALID_HANDLE.OPERATION_NOT_FOUND",
        messageParameters = Map("handle" -> v.getOperationId))
    }
    if (!executeHolder.reattachable) {
      logWarning(s"Reattach to not reattachable operation.")
      throw new SparkSQLException(
        errorClass = "INVALID_CURSOR.NOT_REATTACHABLE",
        messageParameters = Map.empty)
    }

    try {
      val responseSender =
        new ExecuteGrpcResponseSender[proto.ExecutePlanResponse](executeHolder, responseObserver)
      if (v.hasLastResponseId) {
        // start from response after lastResponseId
        executeHolder.attachAndRunGrpcResponseSender(responseSender, v.getLastResponseId)
      } else {
        // start from the start of the stream.
        executeHolder.attachAndRunGrpcResponseSender(responseSender)
      }
    } finally {
      // Reattachable executions do not free the execution here, but client needs to call
      // ReleaseExecute RPC.
      // TODO We mark in the ExecuteHolder that RPC detached.
    }
  }
}
