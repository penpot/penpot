;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.fonts
  (:require
   [clojure.spec.alpha :as s]
   [vertx.web.client :as vwc]
   [mount.core :as mount :refer [defstate]]
   [promesa.core :as p]
   [uxbox.core :refer [system]]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.services.core :as sv]
   [uxbox.services.users :as users]
   [uxbox.util.transit :as t]
   [uxbox.util.spec :as us]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.data :as data]
   [uxbox.util.exceptions :as ex]))

(defstate webclient
  :start (vwc/create system)
  :stop (.close webclient))

(s/def ::google-fonts any?)

(def fonts-url
  "https://www.googleapis.com/webfonts/v1/webfonts?key=")

(def api-key
  "AIzaSyD3iPn7K0sJp5oOi3BRohLDuAA2SKOFJw4")

(defn fetch-and-store
  []
  (let [ses (vwc/session webclient)]
    (-> (vwc/get ses (str fonts-url api-key))
        (p/then' (fn [{:keys [status body]}]
                  (get (t/decode body) "items" [])))
        (p/then' data/normalize-attrs)
        (p/then (fn [result]
                  (-> (sv/mutation {::sv/type :upsert-kvstore
                                    :key "google-fonts"
                                    :user uuid/zero
                                    :value result})
                      (p/catch (fn [err]
                                 (prn "KAKA" err)
                                 (throw err)))
                      (p/then (constantly result))))))))

(sv/defquery ::google-fonts
  "Returns a cached version of google fonts."
  [params]
  (-> (sv/query {::sv/type :kvstore-entry
                 :key "google-fonts"
                 :user uuid/zero})
      (p/catch (fn [err]
                 (let [edata (ex-data err)]
                   (if (= :not-found (:type edata))
                     (fetch-and-store)
                     (p/rejected err)))))))
