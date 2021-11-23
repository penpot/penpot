;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.embed
  (:require
   [app.main.ui.hooks :as hooks]
   [app.util.http :as http]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(def context (mf/create-context false))

(defn use-data-uris [urls]
  (let [embed? (mf/use-ctx context)
        urls (hooks/use-equal-memo urls)
        uri-data (mf/use-ref {})
        state (mf/use-state 0)]

    (hooks/use-effect-ssr
     (mf/deps embed? urls)
     (fn []
       (let [;; When not active the embedding we return the URI
             url-mapping (fn [obs]
                           (if embed?
                             (rx/merge-map http/fetch-data-uri obs)
                             (rx/map identity obs)))

             sub (->> (rx/from urls)
                      (url-mapping)
                      (rx/reduce conj {})
                      (rx/subs (fn [data]
                                 (when-not (= data (mf/ref-val uri-data))
                                   (mf/set-ref-val! uri-data data)
                                   (reset! state inc)))))]
         #(when sub
            (rx/dispose! sub)))))

    ;; Use ref so if the urls are cached will return immediately instead of the
    ;; next render
    (mf/ref-val uri-data)))
