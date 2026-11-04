;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.export-test
  "The asset-export shapes both runtimes share: stored JSON (every id
  and enum a string) decodes to typed values with the repo transformer,
  and typed values decode to themselves. Runs on JVM and JS because the
  backend writes the row and the exporter reads the claim."
  (:require
   [app.common.export :as export]
   [app.common.schema :as sm]
   [clojure.test :as t]))

(def ^:private stored-item
  "One item as the job row holds it: plain JSON, strings everywhere."
  {:file-id   "c9c0b00d-4f50-8024-8008-d07a282a13ee"
   :page-id   "c9c0b00d-4f50-8024-8008-d07a282a13ef"
   :object-id "1ac0612f-1ee8-8023-8008-d07a2aa96040"
   :type      "png"
   :name      "Board"
   :suffix    ""
   :scale     2})

(def ^:private decode
  (sm/decoder export/schema:export-item sm/json-transformer))

(t/deftest stored-strings-decode-to-typed-values
  (let [item (decode stored-item)]
    (t/testing "ids decode to uuids"
      (t/is (uuid? (:file-id item)))
      (t/is (uuid? (:page-id item)))
      (t/is (uuid? (:object-id item)))
      (t/is (= #uuid "1ac0612f-1ee8-8023-8008-d07a2aa96040"
               (:object-id item))))
    (t/testing "the type decodes to a keyword"
      (t/is (= :png (:type item))))
    (t/testing "plain values ride through untouched"
      (t/is (= "Board" (:name item)))
      (t/is (= "" (:suffix item)))
      (t/is (= 2 (:scale item))))))

(t/deftest typed-values-decode-to-themselves
  (let [typed {:file-id   #uuid "c9c0b00d-4f50-8024-8008-d07a282a13ee"
               :page-id   #uuid "c9c0b00d-4f50-8024-8008-d07a282a13ef"
               :object-id #uuid "1ac0612f-1ee8-8023-8008-d07a2aa96040"
               :type      :png
               :name      "Board"
               :suffix    ""
               :scale     2}]
    (t/is (= typed (decode typed)))))

(t/deftest an-item-without-suffix-is-not-a-valid-export
  ;; The renderer needs the suffix always (it builds the file name), so
  ;; the contract requires it: the job is refused at creation instead of
  ;; failing at render time.
  (let [item (-> stored-item decode (dissoc :suffix))]
    (t/is (not (sm/valid? export/schema:params {:exports [item]})))
    (t/is (sm/valid? export/schema:params {:exports [(assoc item :suffix "")]}))))

(t/deftest params-schema-owns-kind-and-options
  (let [decode-params (sm/decoder export/schema:params sm/json-transformer)
        params        (decode-params {:exports [stored-item]
                                      :kind    "frames"
                                      :name    "the export"})]
    (t/is (= :frames (:kind params)))
    (t/is (= "the export" (:name params)))
    (t/is (= 1 (count (:exports params))))))
