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

package es.alvsanand.gdc.core.util

/**
  * Created by alvsanand on 10/12/16.
  */
object ReflectionUtils {

  sealed trait ReflectableObject {
    def getV(name: String): Any

    def setV(name: String, value: Any): Unit
  }

  implicit def reflector(ref: AnyRef): ReflectableObject = new ReflectableObject {
    def getV(name: String): Any = {
      val field = ref.getClass.getDeclaredFields.find(_.getName.endsWith(s"$name"))

      if(field.isDefined) {
        field.get.setAccessible(true)
        field.get.get(ref)
      }
      else {
        None
      }
    }

    def setV(name: String, value: Any): Unit = {
      val field = ref.getClass.getDeclaredFields.find(_.getName.endsWith(s"$name"))

      if(field.isDefined) {
        field.get.setAccessible(true)
        field.get.set(ref, value.asInstanceOf[AnyRef])
      }
    }
  }
}
