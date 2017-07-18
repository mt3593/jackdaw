(ns jackdaw.serdes.avro2
  (:require [jackdaw.serdes.avro2.impl :as impl]
            [clojure.future :refer [uuid? boolean? bytes? double?]])
  (:import (org.apache.kafka.common.serialization Serdes)
           (java.util UUID Map HashMap)
           (org.apache.avro.generic GenericData$Record GenericData$Array GenericData$EnumSymbol)
           (org.apache.avro Schema$ArraySchema Schema)))

(defprotocol SchemaType
  (avro->clj [schema-type avro-data])
  (clj->avro [schema-type clj-data]))

(defn- dispatch-on-type-fields [schema]
  (let [base-type (impl/base-type-name schema)
        logical-type (impl/logical-type-name schema)]
    (if logical-type
      {:type base-type :logical-type logical-type}
      {:type base-type})))

(defmulti schema-type dispatch-on-type-fields)

;; Primitive Types

;;; Boolean

(defrecord BooleanType []
  SchemaType
  (avro->clj [_ x]
    (assert (boolean? x))
    x)
  (clj->avro [_ x]
    (assert (boolean? x))
    x))

(defmethod schema-type {:type "boolean"} [_]
  (BooleanType.))

;;; Bytes

(defrecord BytesType []
  SchemaType
  (avro->clj [_ x]
    (assert (bytes? x))
    x)
  (clj->avro [_ x]
    (assert (bytes? x))
    x))

(defmethod schema-type {:type "bytes"} [_]
  (BytesType.))

;;; Double

(defrecord DoubleType []
  SchemaType
  (avro->clj [_ x]
    (assert (instance? Double x))
    x)
  (clj->avro [_ x]
    (assert (instance? Double x))
    x))

(defmethod schema-type {:type "double"} [_]
  (DoubleType.))

;;; Float

(defrecord FloatType []
  SchemaType
  (avro->clj [_ x]
    (assert (instance? Float x))
    x)
  (clj->avro [_ x]
    (assert (instance? Float x))
    x))

(defmethod schema-type {:type "float"} [_]
  (FloatType.))

;;; Int

(defrecord IntType []
  SchemaType
  (avro->clj [_ x]
    (assert (integer? x))
    x)
  (clj->avro [_ x]
    (assert (integer? x))
    x))

(defmethod schema-type {:type "int"} [_]
  (IntType.))

;;; Long

(defrecord LongType []
  SchemaType
  (avro->clj [_ x]
    (assert (instance? Long x))
    x)
  (clj->avro [_ x]
    (assert (instance? Long x))
    x))

(defmethod schema-type {:type "long"} [_]
  (LongType.))

;;; String

(defrecord StringType []
  SchemaType
  (avro->clj [_ x]
    (assert (string? x))
    x)
  (clj->avro [_ x]
    (assert (string? x))
    x))

(defmethod schema-type {:type "string"} [_]
  (StringType.))

;;; Null

(defrecord NullType []
  SchemaType
  (avro->clj [_ x]
    (assert (nil? x))
    x)
  (clj->avro [_ x]
    (assert (nil? x))
    x))

(defmethod schema-type {:type "null"} [_]
  (NullType.))

;; Complex Types

;;; Array

(defrecord ArrayType [schema]
  SchemaType
  (avro->clj [_ java-collection]
    (let [element-type (.getElementType ^Schema$ArraySchema schema)
          element-schema (schema-type element-type)]
      (mapv #(avro->clj element-schema %) java-collection)))
  (clj->avro [_ clj-seq]
    clj-seq))

(defmethod schema-type {:type "array"} [schema]
  (ArrayType. schema))

;;; Enum

(defrecord EnumType [schema]
  SchemaType
  (avro->clj [_ avro-enum]
    (-> (.toString avro-enum)
        (keyword)))
  (clj->avro [_ clj-keyword]
    (->> (name clj-keyword)
         (GenericData$EnumSymbol. schema))))

(defmethod schema-type {:type "enum"} [schema]
  (EnumType. schema))

;;; Fixed

(defrecord FixedType []
  SchemaType
  (avro->clj [_ fixed]
    (throw (UnsupportedOperationException. "Not implemented")))
  (clj->avro [_ fixed]
    (throw (UnsupportedOperationException. "Not implemented"))))

(defmethod schema-type {:type "fixed"} [_]
  (FixedType.))

;;; Map

(defrecord MapType [schema]
  SchemaType
  (avro->clj [_ avro-map]
    (->> (for [[k v] avro-map]
           [(keyword k) v])
         (into {})))
  (clj->avro [_ clj-map]
    (reduce-kv (fn [acc k v]
                 (let [value-type (.getValueType schema)
                       value-schema (schema-type value-type)
                       new-k (name k)
                       new-v (clj->avro value-schema v)]
                   (.put acc new-k new-v)
                   acc))
               (HashMap.)
               clj-map)))

(defmethod schema-type {:type "map"} [schema]
  (MapType. schema))

;;; Record

(defrecord RecordType [schema]
  SchemaType
  (avro->clj [_ avro-record]
    (let [fields (-> (.getSchema avro-record)
                     (.getFields))]
      (->> (for [field fields
                 :let [name (.name field)
                       schema (schema-type (.schema field))
                       value (.get avro-record name)]]
             [(keyword name) (avro->clj schema value)])
           (into {}))))
  (clj->avro [_ clj-map]
    (reduce-kv (fn [acc k v]
                 (let [new-k (name k)
                       child-schema (-> (.getField schema new-k)
                                        (.schema)
                                        (schema-type))
                       new-v (clj->avro child-schema v)]
                   (.put acc new-k new-v)
                   acc))
               (GenericData$Record. schema)
               clj-map)))

(defmethod schema-type {:type "record"} [schema]
  (RecordType. schema))

;;; Union

(defrecord UnionType []
  SchemaType
  (avro->clj [_ union]
    (throw (UnsupportedOperationException. "Not implemented")))
  (clj->avro [_ union]
    (throw (UnsupportedOperationException. "Not implemented"))))

(defmethod schema-type {:type "union"} []
  (UnionType.))

;; Serde Factory

(defn avro-serde [{:keys [key? schema-str] :as config}]
  (let [schema (impl/parse-schema-str schema-str)
        base-opts {:key? (or key? false)
                   :registry-client nil
                   :base-config {"schema.registry.url" nil}}
        schema-type (schema-type schema config)
        serializer (impl/new-serializer
                    (assoc base-opts :convert-fn #(avro->clj schema-type %)))
        deserializer (impl/new-deserializer
                      (assoc base-opts :convert-fn #(clj->avro schema-type %)))]
    (Serdes/serdeFrom serializer deserializer)))
