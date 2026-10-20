;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.svg-upload-test
  "Tests for the media upload triggered from an SVG import."
  (:require
   [app.common.transit :as tr]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.svg-upload :as svg]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.http :as http]))

(defn- image-node
  [href]
  {:tag :image
   :attrs {:href href :width "10" :height "20"}
   :content []})

(t/deftest upload-images-drops-svg-only-attrs
  (t/testing "media upload params do not carry svg-only attrs"
    (t/async done
      (let [file-id (uuid/next)
            bodies  (atom [])

            fetch-mock
            (fn [url opts]
              (swap! bodies conj {:cmd  (http/url->cmd url)
                                  :body (.-body opts)})
              (js/Promise.resolve
               (http/make-transit-response
                {:id      (uuid/next)
                 :file-id file-id
                 :name    "pic"
                 :mtype   "image/png"
                 :width   10
                 :height  20})))

            orig     (http/install-fetch-mock! fetch-mock)
            svg-data {:tag :svg
                      :attrs {}
                      :content [(image-node "https://example.com/pic.png")]}]

        (->> (svg/upload-images svg-data file-id)
             (rx/subs!
              (fn [_] nil)
              (fn [err]
                (http/restore-fetch! orig)
                (t/is false (str "unexpected error: " (ex-message err)))
                (done))
              (fn []
                (http/restore-fetch! orig)
                (let [{:keys [cmd body]} (first @bodies)
                      params (tr/decode-str body)]
                  (t/is (= :create-file-media-object-from-url cmd))
                  (t/is (= "https://example.com/pic.png" (:url params)))
                  (t/is (not (contains? params :href)))
                  (t/is (not (contains? params :width)))
                  (t/is (not (contains? params :height))))
                (done))))))))
