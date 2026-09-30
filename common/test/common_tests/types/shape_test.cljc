;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.shape-test
  (:require
   [app.common.json :as json]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.types.shape :as tsh :refer [schema:shape]]
   [clojure.test :as t]))

;; Shape schema hardening for https://github.com/penpot/penpot/issues/11947.
;; Stored svg attribute keys are kebab-case: `:svg-attrs` is closed over
;; the whitelist in stored spelling (derived from the same set birth
;; uses, so the two cannot drift apart), and `:svg-defs` nodes share one
;; node schema with svg-raw `:content`.

(defn- sample-shape
  "A generated shape: valid by construction, a base to assoc svg keys onto."
  []
  (first (sg/sample (sg/generator schema:shape) {:size 20})))

(t/deftest svg-attrs-accepts-kebab-maps
  (let [shape (assoc (sample-shape)
                     :svg-attrs {:fill-rule "evenodd"
                                 :stroke-width "2"
                                 :stroke-style "dotted"
                                 :style {:fill-opacity "0.5"}})]
    (t/is (tsh/check-shape-attrs shape) "kebab svg-attrs validate")))

(t/deftest svg-attrs-rejects-non-maps-and-unknown-keys
  (let [shape (sample-shape)
        throws? (fn [s]
                  (try (tsh/check-shape-attrs s) false
                       (catch #?(:clj Exception :cljs js/Error) _ true)))]
    (t/is (throws? (assoc shape :svg-attrs "junk"))
          "non-map svg-attrs fail validation")
    (t/is (throws? (assoc shape :svg-attrs {:fillRule "evenodd"}))
          "camel keys fail validation: storage is kebab-only")
    (t/is (throws? (assoc shape :svg-attrs {:data-foo "x"}))
          "unknown keys fail validation")))

(t/deftest svg-def-node-validates-tag-and-attrs
  (let [shape (sample-shape)
        node  {:tag :linearGradient
               :attrs {:id "g1"
                       :gradient-units "userSpaceOnUse"}
               :content [{:tag :stop
                          :attrs {:offset "0"
                                  :stop-color "#ffffff"}
                          :content []}]}
        with-defs (fn [defs] (assoc shape :svg-defs defs))
        throws? (fn [s]
                  (try (tsh/check-shape-attrs s) false
                       (catch #?(:clj Exception :cljs js/Error) _ true)))]
    (t/is (tsh/check-shape-attrs (with-defs {"g1" node}))
          "string outer keys validate (birth spelling)")
    (t/is (tsh/check-shape-attrs (with-defs {:g1 node}))
          "keyword outer keys validate (post-decode spelling)")
    (t/is (throws? (with-defs {"g1" (assoc node :tag "linearGradient")}))
          "non-keyword tags fail validation")
    (t/is (throws? (with-defs {"g1" (assoc node :attrs {:data-x "1"})}))
          "unknown def attrs fail validation")
    (t/is (throws? (with-defs {"g1" (assoc node :attrs "junk")}))
          "non-map def attrs fail validation")))

(t/deftest svg-content-keeps-unknown-keys
  ;; `:content` attrs are spelling-only: the sidebar reads keys outside
  ;; the whitelist (e.g. `:stroke-style`), so the schema stays open.
  ;; NOTE: the sample seed is pinned. Unpinned sampling flaked (~1%
  ;; of runs): some seeds deal zero `:svg-raw` shapes in 50 samples,
  ;; `first` yields nil, and the `assoc` below builds a typeless
  ;; `{:content …}` map that fails the `:multi` dispatch on `:type`.
  (let [shape (->> (sg/sample (sg/generator schema:shape) {:size 50 :seed 42})
                   (filter #(= :svg-raw (:type %)))
                   (first)
                   (#(assoc % :content {:tag :g
                                        :attrs {:stroke-style "dotted"
                                                :stroke-width "2"
                                                :data-foo "keep-me"}
                                        :content []})))]
    (t/is (some? shape) "an svg-raw shape was sampled")
    (t/is (tsh/check-shape-attrs shape)
          ":stroke-style in content attrs validates")))

(t/deftest generated-shapes-carry-no-svg-provenance
  ;; The `:gen/fmap` mitigation: svg provenance is import-only data,
  ;; random attrs would pollute every generative test.
  (let [shapes (sg/sample (sg/generator schema:shape) {:size 20})]
    (t/is (= 20 (count shapes)))
    (doseq [shape shapes]
      (t/is (not (contains? shape :svg-attrs)) "no generated svg-attrs")
      (t/is (not (contains? shape :svg-defs)) "no generated svg-defs"))))

(t/deftest svg-attrs-survive-json-encoding-untouched
  ;; What binfile export relies on: kebab keys (and nested `:style`)
  ;; round-trip byte-identical through the JSON layer.
  (let [attrs  {:fill-rule "evenodd"
                :stroke-style "dotted"
                :style {:fill-opacity "0.5"}}
        shape  (assoc (sample-shape) :svg-attrs attrs)
        encode (sm/encoder schema:shape (sm/json-transformer))
        data   (-> (encode shape)
                   (json/encode :key-fn json/write-camel-key)
                   (json/decode :key-fn json/read-kebab-key))]
    (t/is (= attrs (:svg-attrs data)))))
