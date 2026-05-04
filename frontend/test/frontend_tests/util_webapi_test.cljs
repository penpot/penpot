;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.util-webapi-test
  (:require
   [app.util.webapi :as wapi]
   [cljs.test :as t :include-macros true]))

(t/deftest data-uri->blob-supports-base64
  (t/async done
    (let [blob (wapi/data-uri->blob "data:text/plain;base64,SGVsbG8=")]
      (-> (.text blob)
          (.then (fn [text]
                   (t/is (= "text/plain" (.-type blob)))
                   (t/is (= "Hello" text))
                   (done)))
          (.catch (fn [err]
                    (t/is false (str "unexpected error: " err))
                    (done)))))))

(t/deftest data-uri->blob-supports-utf8-data
  (t/async done
    (let [blob (wapi/data-uri->blob "data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%3E%3C%2Fsvg%3E")]
      (-> (.text blob)
          (.then (fn [text]
                   (t/is (= "image/svg+xml" (.-type blob)))
                   (t/is (= "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>" text))
                   (done)))
          (.catch (fn [err]
                    (t/is false (str "unexpected error: " err))
                    (done)))))))
