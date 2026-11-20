;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.util-zip-test
  (:require
   [app.util.zip :as-alias uz]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]))

(t/deftest read-as-text-nil-entry-raises-typed-error
  (t/testing "read-as-text guards against nil entry"
    (t/is (thrown-with-msg? js/Error #"nil"
                            (app.util.zip/read-as-text nil)))))

(t/deftest writer-does-not-write-the-extended-timestamp-extra-field
  (t/async done
    (let [writer (-> (app.util.zip/bytes-writer)
                     (app.util.zip/writer))]
      (-> (p/then (app.util.zip/add writer "sample.txt" "sample")
                  (fn [_]
                    (app.util.zip/close writer)))
          (p/then (fn [bytes]
                    (let [view (js/DataView. (.-buffer bytes)
                                             (.-byteOffset bytes)
                                             (.-byteLength bytes))]
                      ;; Local file header: signature in 0..3, extra-field
                      ;; length in 28..29.
                      (t/is (= 0x04034b50 (.getUint32 view 0 true))
                            "the entry starts with a local file header")
                      (t/is (= 0 (.getUint16 view 28 true))
                            "the local header carries no extra field"))))
          (p/then (fn [_] (done)))
          (p/catch (fn [cause]
                     (t/is false (str "export failed: " cause))
                     (done)))))))
