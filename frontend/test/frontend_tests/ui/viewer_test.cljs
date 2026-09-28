;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.viewer-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.ui.viewer :as viewer]
   [cljs.test :as t :include-macros true]))

(defn- raised-data
  "Return the ex-data of the error raised by calling `f`, or nil when `f`
  returns without raising."
  [f]
  (try
    (f)
    nil
    (catch :default e
      (ex-data e))))

(t/deftest check-file-id-raises-not-found-for-invalid-file-id
  ;; Regression test: the viewer route parses `file-id` with `uuid/parse*`,
  ;; so a missing or malformed id in the URL reaches the viewer as nil. The
  ;; viewer must then raise a not-found error (which the error boundary turns
  ;; into the 404 page) instead of initializing itself with a nil file.
  (t/testing "nil file-id"
    (let [data (raised-data #(viewer/check-file-id! nil))]
      (t/is (= :not-found (:type data)))
      (t/is (= :missing-file-id (:code data)))))

  (t/testing "malformed file-id"
    (let [data (raised-data #(viewer/check-file-id! "not-a-uuid"))]
      (t/is (= :not-found (:type data)))
      (t/is (= :missing-file-id (:code data)))))

  (t/testing "valid uuid passes without raising"
    (t/is (nil? (raised-data #(viewer/check-file-id! (uuid/next)))))))
