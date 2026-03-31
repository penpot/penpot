;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.fressian-test
  "Exhaustive unit tests for app.common.fressian encode/decode functions.

  Tests cover every custom handler registered in the fressian namespace
  (char, java/instant, clj/ratio, clj/map, linked/map, clj/keyword,
   clj/symbol, clj/bigint, clj/set, clj/vector, clj/list, clj/seq,
   linked/set) plus the built-in Fressian primitives (nil, boolean,
   integer, long, double, string, bytes, UUID).

  The file is JVM-only because Fressian is a JVM library."
  (:require
   [app.common.data :as d]
   [app.common.fressian :as fres]
   [clojure.test :as t])
  (:import
   java.time.Instant
   java.time.OffsetDateTime
   java.time.ZoneOffset))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn roundtrip
  "Encode then decode a value; the result must equal the original."
  [v]
  (-> v fres/encode fres/decode))

(defn roundtrip=
  "Returns true when encode→decode produces an equal value."
  [v]
  (= v (roundtrip v)))

;; ---------------------------------------------------------------------------
;; Encode returns a byte array
;; ---------------------------------------------------------------------------

(t/deftest encode-returns-byte-array
  (t/is (bytes? (fres/encode nil)))
  (t/is (bytes? (fres/encode 42)))
  (t/is (bytes? (fres/encode "hello")))
  (t/is (bytes? (fres/encode {:a 1})))
  (t/is (bytes? (fres/encode [])))
  (t/is (pos? (alength ^bytes (fres/encode 0))))
  (t/testing "different values produce different byte arrays"
    (t/is (not= (vec (fres/encode 1)) (vec (fres/encode 2))))))

;; ---------------------------------------------------------------------------
;; nil
;; ---------------------------------------------------------------------------

(t/deftest nil-roundtrip
  (t/is (nil? (roundtrip nil))))

;; ---------------------------------------------------------------------------
;; Booleans
;; ---------------------------------------------------------------------------

(t/deftest boolean-roundtrip
  (t/is (true? (roundtrip true)))
  (t/is (false? (roundtrip false))))

;; ---------------------------------------------------------------------------
;; Integers and longs
;; ---------------------------------------------------------------------------

(t/deftest integer-roundtrip
  (t/is (= 0 (roundtrip 0)))
  (t/is (= 1 (roundtrip 1)))
  (t/is (= -1 (roundtrip -1)))
  (t/is (= 42 (roundtrip 42)))
  (t/is (= Integer/MAX_VALUE (roundtrip Integer/MAX_VALUE)))
  (t/is (= Integer/MIN_VALUE (roundtrip Integer/MIN_VALUE))))

(t/deftest long-roundtrip
  (t/is (= Long/MAX_VALUE (roundtrip Long/MAX_VALUE)))
  (t/is (= Long/MIN_VALUE (roundtrip Long/MIN_VALUE)))
  (t/is (= 1000000000000 (roundtrip 1000000000000))))

;; ---------------------------------------------------------------------------
;; Doubles / floats
;; ---------------------------------------------------------------------------

(t/deftest double-roundtrip
  (t/is (= 0.0 (roundtrip 0.0)))
  (t/is (= 3.14 (roundtrip 3.14)))
  (t/is (= -2.718 (roundtrip -2.718)))
  (t/is (= Double/MAX_VALUE (roundtrip Double/MAX_VALUE)))
  (t/is (= Double/MIN_VALUE (roundtrip Double/MIN_VALUE)))
  (t/is (Double/isInfinite ^double (roundtrip Double/POSITIVE_INFINITY)))
  (t/is (Double/isInfinite ^double (roundtrip Double/NEGATIVE_INFINITY)))
  (t/is (Double/isNaN ^double (roundtrip Double/NaN))))

;; ---------------------------------------------------------------------------
;; Strings
;; ---------------------------------------------------------------------------

(t/deftest string-roundtrip
  (t/is (= "" (roundtrip "")))
  (t/is (= "hello" (roundtrip "hello")))
  (t/is (= "hello world" (roundtrip "hello world")))
  (t/is (= "αβγδ" (roundtrip "αβγδ")))
  (t/is (= "emoji: 🎨" (roundtrip "emoji: 🎨")))
  (t/is (= (apply str (repeat 10000 "x")) (roundtrip (apply str (repeat 10000 "x"))))))

;; ---------------------------------------------------------------------------
;; Characters  (custom "char" handler)
;; ---------------------------------------------------------------------------

(t/deftest char-roundtrip
  (t/is (= \a (roundtrip \a)))
  (t/is (= \A (roundtrip \A)))
  (t/is (= \space (roundtrip \space)))
  (t/is (= \newline (roundtrip \newline)))
  (t/is (= \0 (roundtrip \0)))
  (t/is (= \ü (roundtrip \ü)))
  (t/testing "char type is preserved"
    (t/is (char? (roundtrip \x)))))

;; ---------------------------------------------------------------------------
;; Keywords  (custom "clj/keyword" handler)
;; ---------------------------------------------------------------------------

(t/deftest keyword-roundtrip
  (t/is (= :foo (roundtrip :foo)))
  (t/is (= :bar (roundtrip :bar)))
  (t/is (= :ns/foo (roundtrip :ns/foo)))
  (t/is (= :app.common.data/something (roundtrip :app.common.data/something)))
  (t/testing "keyword? is preserved"
    (t/is (keyword? (roundtrip :anything))))
  (t/testing "namespace is preserved"
    (let [kw :my-ns/my-name]
      (t/is (= (namespace kw) (namespace (roundtrip kw))))
      (t/is (= (name kw) (name (roundtrip kw)))))))

;; ---------------------------------------------------------------------------
;; Symbols  (custom "clj/symbol" handler)
;; ---------------------------------------------------------------------------

(t/deftest symbol-roundtrip
  (t/is (= 'foo (roundtrip 'foo)))
  (t/is (= 'bar (roundtrip 'bar)))
  (t/is (= 'ns/foo (roundtrip 'ns/foo)))
  (t/is (= 'clojure.core/map (roundtrip 'clojure.core/map)))
  (t/testing "symbol? is preserved"
    (t/is (symbol? (roundtrip 'anything))))
  (t/testing "namespace is preserved"
    (let [sym 'my-ns/my-name]
      (t/is (= (namespace sym) (namespace (roundtrip sym))))
      (t/is (= (name sym) (name (roundtrip sym)))))))

;; ---------------------------------------------------------------------------
;; Vectors  (custom "clj/vector" handler)
;; ---------------------------------------------------------------------------

(t/deftest vector-roundtrip
  (t/is (= [] (roundtrip [])))
  (t/is (= [1 2 3] (roundtrip [1 2 3])))
  (t/is (= [:a :b :c] (roundtrip [:a :b :c])))
  (t/is (= [nil nil nil] (roundtrip [nil nil nil])))
  (t/is (= [[1 2] [3 4]] (roundtrip [[1 2] [3 4]])))
  (t/is (= ["hello" :world 42] (roundtrip ["hello" :world 42])))
  (t/testing "vector? is preserved"
    (t/is (vector? (roundtrip [1 2 3])))))

;; ---------------------------------------------------------------------------
;; Sets  (custom "clj/set" handler)
;; ---------------------------------------------------------------------------

(t/deftest set-roundtrip
  (t/is (= #{} (roundtrip #{})))
  (t/is (= #{1 2 3} (roundtrip #{1 2 3})))
  (t/is (= #{:a :b :c} (roundtrip #{:a :b :c})))
  (t/is (= #{"x" "y"} (roundtrip #{"x" "y"})))
  (t/testing "set? is preserved"
    (t/is (set? (roundtrip #{:foo})))))

;; ---------------------------------------------------------------------------
;; Maps  (custom "clj/map" handler)
;; ---------------------------------------------------------------------------

(t/deftest small-map-roundtrip
  "Maps with fewer than 8 entries decode as PersistentArrayMap."
  (t/is (= {} (roundtrip {})))
  (t/is (= {:a 1} (roundtrip {:a 1})))
  (t/is (= {:a 1 :b 2} (roundtrip {:a 1 :b 2})))
  (t/is (= {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7} (roundtrip {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7})))
  (t/testing "map? is preserved"
    (t/is (map? (roundtrip {:x 1})))))

(t/deftest large-map-roundtrip
  "Maps with 8+ entries decode as PersistentHashMap (>= 16 kvs in list)."
  (let [large (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 20)))]
    (t/is (= large (roundtrip large)))
    (t/is (map? (roundtrip large)))))

(t/deftest map-with-mixed-keys-roundtrip
  (let [m {:keyword-key 1
           "string-key" 2
           42            3}]
    (t/is (= m (roundtrip m)))))

(t/deftest map-with-nil-value-roundtrip
  (t/is (= {:a nil :b 2} (roundtrip {:a nil :b 2}))))

;; ---------------------------------------------------------------------------
;; Sequences  (custom "clj/seq" handler)
;; ---------------------------------------------------------------------------

(t/deftest seq-roundtrip
  (let [s (seq [1 2 3])]
    (t/is (= (sequence s) (roundtrip s))))
  (let [s (map inc [1 2 3])]
    (t/is (= (sequence s) (roundtrip s))))
  (t/testing "result is a sequence"
    (t/is (seq? (roundtrip (seq [1 2 3]))))))

;; ---------------------------------------------------------------------------
;; Ratio  (custom "clj/ratio" handler)
;; ---------------------------------------------------------------------------

(t/deftest ratio-roundtrip
  (t/is (= 1/3 (roundtrip 1/3)))
  (t/is (= 22/7 (roundtrip 22/7)))
  (t/is (= -5/6 (roundtrip -5/6)))
  (t/is (= 1/1000000 (roundtrip 1/1000000)))
  (t/testing "ratio? is preserved"
    (t/is (ratio? (roundtrip 1/3)))))

;; ---------------------------------------------------------------------------
;; BigInt  (custom "clj/bigint" handler)
;; ---------------------------------------------------------------------------

(t/deftest bigint-roundtrip
  (t/is (= 0N (roundtrip 0N)))
  (t/is (= 1N (roundtrip 1N)))
  (t/is (= -1N (roundtrip -1N)))
  (t/is (= 123456789012345678901234567890N (roundtrip 123456789012345678901234567890N)))
  (t/is (= -999999999999999999999999999999N (roundtrip -999999999999999999999999999999N)))
  (t/testing "bigint? is preserved"
    (t/is (instance? clojure.lang.BigInt (roundtrip 42N)))))

;; ---------------------------------------------------------------------------
;; java.time.Instant  (custom "java/instant" handler)
;; ---------------------------------------------------------------------------

(t/deftest instant-roundtrip
  (let [now (Instant/now)]
    (t/is (= (.toEpochMilli now) (.toEpochMilli ^Instant (roundtrip now)))))
  (t/testing "epoch zero"
    (let [epoch (Instant/ofEpochMilli 0)]
      (t/is (= epoch (roundtrip epoch)))))
  (t/testing "far past"
    (let [past (Instant/ofEpochMilli -62135596800000)]
      (t/is (= past (roundtrip past)))))
  (t/testing "far future"
    (let [future (Instant/ofEpochMilli 32503680000000)]
      (t/is (= future (roundtrip future)))))
  (t/testing "result type is Instant"
    (t/is (instance? Instant (roundtrip (Instant/now))))))

;; ---------------------------------------------------------------------------
;; java.time.OffsetDateTime  (written as "java/instant", read back as Instant)
;; ---------------------------------------------------------------------------

(t/deftest offset-date-time-roundtrip
  (t/testing "OffsetDateTime is written and decoded as Instant (millis preserved)"
    (let [odt  (OffsetDateTime/now ZoneOffset/UTC)
          millis (.toEpochMilli (.toInstant odt))
          result (roundtrip odt)]
      (t/is (instance? Instant result))
      (t/is (= millis (.toEpochMilli ^Instant result)))))
  (t/testing "non-UTC offset"
    (let [odt  (OffsetDateTime/now (ZoneOffset/ofHours 5))
          millis (.toEpochMilli (.toInstant odt))
          result (roundtrip odt)]
      (t/is (= millis (.toEpochMilli ^Instant result))))))

;; ---------------------------------------------------------------------------
;; Ordered map  (custom "linked/map" handler)
;; ---------------------------------------------------------------------------

(t/deftest ordered-map-roundtrip
  (t/is (= (d/ordered-map) (roundtrip (d/ordered-map))))
  (t/is (= (d/ordered-map :a 1) (roundtrip (d/ordered-map :a 1))))
  (t/is (= (d/ordered-map :a 1 :b 2 :c 3) (roundtrip (d/ordered-map :a 1 :b 2 :c 3))))
  (t/testing "ordered-map? is preserved"
    (t/is (d/ordered-map? (roundtrip (d/ordered-map :x 1 :y 2)))))
  (t/testing "insertion order is preserved"
    (let [om (d/ordered-map :c 3 :a 1 :b 2)
          rt (roundtrip om)]
      (t/is (= [:c :a :b] (vec (keys rt))))))
  (t/testing "large ordered-map"
    (let [om (reduce (fn [m i] (assoc m (keyword (str "k" i)) i))
                     (d/ordered-map)
                     (range 20))
          rt (roundtrip om)]
      (t/is (d/ordered-map? rt))
      (t/is (= om rt))
      (t/is (= (keys om) (keys rt))))))

;; ---------------------------------------------------------------------------
;; Ordered set  (custom "linked/set" handler)
;; ---------------------------------------------------------------------------

(t/deftest ordered-set-roundtrip
  (t/is (= (d/ordered-set) (roundtrip (d/ordered-set))))
  (t/is (= (d/ordered-set :a) (roundtrip (d/ordered-set :a))))
  (t/is (= (d/ordered-set :a :b :c) (roundtrip (d/ordered-set :a :b :c))))
  (t/testing "ordered-set? is preserved"
    (t/is (d/ordered-set? (roundtrip (d/ordered-set :x :y)))))
  (t/testing "insertion order is preserved"
    (let [os (d/ordered-set :c :a :b)
          rt (roundtrip os)]
      (t/is (= [:c :a :b] (vec rt)))))
  (t/testing "large ordered-set"
    (let [os (reduce conj (d/ordered-set) (range 20))
          rt (roundtrip os)]
      (t/is (d/ordered-set? rt))
      (t/is (= os rt)))))

;; ---------------------------------------------------------------------------
;; UUID  (handled by built-in Fressian handlers)
;; ---------------------------------------------------------------------------

(t/deftest uuid-roundtrip
  (let [id (java.util.UUID/randomUUID)]
    (t/is (= id (roundtrip id))))
  (t/testing "nil UUID"
    (let [nil-uuid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000000")]
      (t/is (= nil-uuid (roundtrip nil-uuid)))))
  (t/testing "max UUID"
    (let [max-uuid (java.util.UUID/fromString "ffffffff-ffff-ffff-ffff-ffffffffffff")]
      (t/is (= max-uuid (roundtrip max-uuid)))))
  (t/testing "specific well-known UUID"
    (let [id (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")]
      (t/is (= id (roundtrip id)))))
  (t/testing "uuid? is preserved"
    (t/is (uuid? (roundtrip (java.util.UUID/randomUUID))))))

;; ---------------------------------------------------------------------------
;; Nested and mixed structures
;; ---------------------------------------------------------------------------

(t/deftest nested-map-roundtrip
  (let [nested {:a {:b {:c 42 :d [1 2 3]} :e :keyword} :f "string"}]
    (t/is (= nested (roundtrip nested)))))

(t/deftest map-with-vector-values
  (let [m {:shapes [1 2 3] :colors [:red :green :blue]}]
    (t/is (= m (roundtrip m)))))

(t/deftest vector-of-maps
  (let [v [{:id 1 :name "a"} {:id 2 :name "b"} {:id 3 :name "c"}]]
    (t/is (= v (roundtrip v)))))

(t/deftest mixed-collection-types
  (let [data {:vec     [1 2 3]
              :set     #{:a :b :c}
              :map     {:nested true}
              :kw      :some/keyword
              :sym     'some/symbol
              :bigint  12345678901234567890N
              :ratio   22/7
              :str     "hello"
              :num     42
              :bool    true
              :nil-val nil}]
    (t/is (= data (roundtrip data)))))

(t/deftest deeply-nested-structure
  (let [data (reduce (fn [acc i] {:level i :child acc})
                     {:leaf true}
                     (range 20))]
    (t/is (= data (roundtrip data)))))

(t/deftest penpot-like-shape-map
  "Simulates a Penpot shape-like structure with UUIDs, keywords, and nested maps."
  (let [id       (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440001")
        frame-id (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440002")
        shape    {:id       id
                  :frame-id frame-id
                  :type     :rect
                  :name     "My Shape"
                  :x        100.5
                  :y        200.0
                  :width    300.0
                  :height   150.0
                  :fills    [{:fill-color "#FF0000" :fill-opacity 1.0}]
                  :strokes  []
                  :hidden   false
                  :blocked  false}]
    (t/is (= shape (roundtrip shape)))))

(t/deftest penpot-like-objects-map
  "Simulates a Penpot page objects map with multiple shapes."
  (let [ids   (mapv #(java.util.UUID/fromString
                      (format "550e8400-e29b-41d4-a716-%012d" %))
                    (range 5))
        objs  (into {} (map (fn [id] [id {:id id :type :rect :name (str id)}]) ids))
        data  {:objects objs}]
    (t/is (= data (roundtrip data)))))

;; ---------------------------------------------------------------------------
;; Idempotency: encode→decode→encode must yield equal bytes
;; ---------------------------------------------------------------------------

(t/deftest encode-idempotency
  (doseq [v [nil true false 0 1 -1 42 Long/MAX_VALUE 3.14 "" "hello"
             :kw :ns/kw 'sym 'ns/sym
             [] [1 2 3] #{} #{:a} {} {:a 1}
             1/3 42N]]
    (let [enc1 (fres/encode v)
          enc2 (-> v fres/encode fres/decode fres/encode)]
      (t/is (= (vec enc1) (vec enc2))
            (str "Idempotency failed for: " (pr-str v))))))

;; ---------------------------------------------------------------------------
;; Multiple encode/decode roundtrips in sequence (regression / ordering)
;; ---------------------------------------------------------------------------

(t/deftest multiple-roundtrips-are-independent
  (t/testing "encoding multiple values independently does not cross-contaminate"
    (let [a (fres/encode {:key :val-a})
          b (fres/encode {:key :val-b})
          da (fres/decode a)
          db (fres/decode b)]
      (t/is (= {:key :val-a} da))
      (t/is (= {:key :val-b} db))
      (t/is (not= da db)))))

;; ---------------------------------------------------------------------------
;; Edge cases: empty collections
;; ---------------------------------------------------------------------------

(t/deftest empty-collections-roundtrip
  (t/is (= {} (roundtrip {})))
  (t/is (= [] (roundtrip [])))
  (t/is (= #{} (roundtrip #{})))
  (t/is (= "" (roundtrip "")))
  (t/is (= (d/ordered-map) (roundtrip (d/ordered-map))))
  (t/is (= (d/ordered-set) (roundtrip (d/ordered-set)))))

;; ---------------------------------------------------------------------------
;; Edge cases: collections containing nil
;; ---------------------------------------------------------------------------

(t/deftest collections-with-nil-roundtrip
  (t/is (= [nil] (roundtrip [nil])))
  (t/is (= [nil nil nil] (roundtrip [nil nil nil])))
  (t/is (= {:a nil :b nil} (roundtrip {:a nil :b nil})))
  (t/is (= [1 nil 3] (roundtrip [1 nil 3]))))

;; ---------------------------------------------------------------------------
;; Edge cases: single-element collections
;; ---------------------------------------------------------------------------

(t/deftest single-element-collections
  (t/is (= [42] (roundtrip [42])))
  (t/is (= #{:only} (roundtrip #{:only})))
  (t/is (= {:only-key "only-val"} (roundtrip {:only-key "only-val"}))))

;; ---------------------------------------------------------------------------
;; Edge cases: boundary map sizes (ArrayMap/HashMap threshold)
;; ---------------------------------------------------------------------------

(t/deftest map-size-boundary
  (t/testing "7-entry map (below threshold → ArrayMap)"
    (let [m (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 7)))]
      (t/is (= m (roundtrip m)))))
  (t/testing "8-entry map (at/above threshold → may become HashMap)"
    (let [m (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 8)))]
      (t/is (= m (roundtrip m)))))
  (t/testing "16-entry map (well above threshold)"
    (let [m (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 16)))]
      (t/is (= m (roundtrip m))))))

;; ---------------------------------------------------------------------------
;; Edge cases: byte arrays
;; ---------------------------------------------------------------------------

(t/deftest byte-array-roundtrip
  (let [data (byte-array [0 1 2 3 127 -128 -1])]
    (t/is (= (vec data) (vec ^bytes (roundtrip data))))))

;; ---------------------------------------------------------------------------
;; Ordered-map key ordering survives large number of keys
;; ---------------------------------------------------------------------------

(t/deftest ordered-map-key-ordering-stress
  (let [keys-in-order (mapv #(keyword (str "key-" (format "%03d" %))) (range 50))
        om  (reduce (fn [m k] (assoc m k (name k))) (d/ordered-map) keys-in-order)
        rt  (roundtrip om)]
    (t/is (= keys-in-order (vec (keys rt))))))

;; ---------------------------------------------------------------------------
;; Ordered-set element ordering survives large number of elements
;; ---------------------------------------------------------------------------

(t/deftest ordered-set-element-ordering-stress
  (let [elems-in-order (mapv #(keyword (str "elem-" (format "%03d" %))) (range 50))
        os  (reduce conj (d/ordered-set) elems-in-order)
        rt  (roundtrip os)]
    (t/is (= elems-in-order (vec rt)))))

;; ---------------------------------------------------------------------------
;; Complex Penpot-domain: ordered-map with UUID keys and shape values
;; ---------------------------------------------------------------------------

(t/deftest ordered-map-with-uuid-keys
  (let [ids (mapv #(java.util.UUID/fromString
                    (format "550e8400-e29b-41d4-a716-%012d" %))
                  (range 5))
        om  (reduce (fn [m id] (assoc m id {:type :rect :id id}))
                    (d/ordered-map)
                    ids)
        rt  (roundtrip om)]
    (t/is (d/ordered-map? rt))
    (t/is (= om rt))
    (t/is (= (keys om) (keys rt)))))
