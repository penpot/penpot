;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.scripts-decode-file-data-test
  (:require
   [app.common.json :as json]
   [app.common.uuid :as uuid]
   [app.scripts.decode-file-data :as dfd]
   [app.util.blob :as blob]
   [clojure.java.io :as io]
   [clojure.test :as t])
  (:import
   java.util.Base64))

(defn- sample-file-data
  []
  (let [p1 (uuid/next)
        p2 (uuid/next)]
    {:pages [p1 p2]
     :pages-index
     {p1 {:id p1 :name "Accueil1" :objects {:a 1 :b 2}}
      p2 {:id p2 :name "Contact" :objects {:c 3}}}
     :components {(uuid/next) {:name "comp"}}
     :colors {(uuid/next) {:name "red"}}
     :typographies {}}))

(t/deftest sanitize-converts-special-types
  (let [id (uuid/next)
        out (dfd/sanitize {:id id
                           :kw :pages-index
                           :when (java.util.Date. 0)
                           :tags #{:a :b}
                           :raw (.getBytes "hi" "UTF-8")})]
    (t/is (= (str id) (get out "id")))
    (t/is (= "pages-index" (get out "kw")))
    (t/is (string? (get out "when")))
    (t/is (= ["a" "b"] (sort (get out "tags"))))
    (t/is (= {"$bytes" (.encodeToString (Base64/getEncoder) (.getBytes "hi" "UTF-8"))}
             (get out "raw")))))

(t/deftest sanitize-keeps-integers-as-integers
  (t/is (= 2 (dfd/sanitize 2)))
  (t/is (= 2.5 (dfd/sanitize 2.5))))

(t/deftest blob-roundtrip-all-versions-to-json
  (doseq [version [1 3 4 5]]
    (let [data    (sample-file-data)
          encoded (blob/encode data {:version version})
          payload (dfd/blob->json encoded)
          parsed  (json/decode payload)]
      (t/is (map? parsed) (str "version " version " produces a JSON map"))
      (t/is (= 2 (count (get parsed "pages"))) (str "version " version " keeps pages")))))

(t/deftest summary-lists-pages-in-order-with-object-counts
  (let [data    (sample-file-data)
        summary (dfd/summarize-data data)
        pages   (:pages summary)]
    (t/is (= 2 (count pages)))
    (t/is (= ["Accueil1" "Contact"] (mapv :name pages)))
    (t/is (= [2 1] (mapv :objects pages)))
    (t/is (= {:pages 2 :components 1 :colors 1 :typographies 0}
             (:stats summary)))
    (let [pages-json (json/decode (dfd/file-data-pages-json data))]
      (t/is (= ["Accueil1" "Contact"] (mapv #(get % "name") pages-json))))))

(t/deftest cli-decodes-blob-file-to-json
  (let [data     (sample-file-data)
        tmp-in   (java.io.File/createTempFile "blob" ".bin")
        tmp-out  (java.io.File/createTempFile "out" ".json")
        _        (.deleteOnExit tmp-in)
        _        (.deleteOnExit tmp-out)
        _        (with-open [os (io/output-stream tmp-in)]
                   (.write os ^bytes (blob/encode data {:version 5})))
        _        (dfd/-main "--input" (.getAbsolutePath tmp-in)
                            "--output" (.getAbsolutePath tmp-out)
                            "--format" "summary")
        parsed   (json/decode (slurp tmp-out))]
    (t/is (== 2 (get-in parsed ["stats" "pages"])))
    (t/is (= ["Accueil1" "Contact"] (mapv #(get % "name") (get parsed "pages"))))))
