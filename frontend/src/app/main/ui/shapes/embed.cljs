;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.embed
  (:require
   [app.main.ui.hooks :as hooks]
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def context (mf/create-context false))

(defn use-data-uris [urls]
  (let [embed? (mf/use-ctx context)
        urls (hooks/use-equal-memo urls)
        uri-data (mf/use-ref {})
        state (mf/use-state 0)]

    (mf/use-ssr-effect
     (mf/deps embed? urls)
     (fn []
       (let [;; When not active the embedding we return the URI
             url-mapping (fn [obs]
                           (if embed?
                             (->> obs
                                  (rx/merge-map
                                   (fn [uri]
                                     (->> (http/fetch-data-uri uri true)
                                          ;; If fetching give an error we store the URI as its `data-uri`
                                          (rx/catch #(rx/of (hash-map uri uri)))))))
                             (rx/map (fn [uri] {uri uri}) obs)))

             sub (->> (rx/from urls)
                      (rx/filter some?)
                      (url-mapping)
                      (rx/reduce conj {})
                      (rx/subs! (fn [data]
                                  (when-not (= data (mf/ref-val uri-data))
                                    (mf/set-ref-val! uri-data data)
                                    (reset! state inc)))))]
         #(when sub
            (rx/dispose! sub)))))

    ;; Use ref so if the urls are cached will return immediately instead of the
    ;; next render
    (mf/ref-val uri-data)))
