;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.preview
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.shape-tree :as ctst]
   [app.main.data.helpers :as dsh]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.util.code-beautify :as cb]
   [app.util.code-gen :as cg]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(def style-type "css")
(def markup-type "html")


(def page-template
  "<!DOCTYPE html>
<html>
  <head>
    <style>
    %s
    </style>
  </head>
  <body>
  %s
  </body>
</html>")

(defn update-preview-window
  [preview code width height]
  (when preview
    (if (aget preview "load")
      (.load preview code width height)
      (ts/schedule #(update-preview-window preview code width height)))))

(defn shapes->fonts
  [shapes]
  (->> shapes
       (filter cfh/text-shape?)
       (map (comp fonts/get-content-fonts :content))
       (reduce set/union #{})))

(defn update-preview
  [preview shape-id]
  (ptk/reify ::update-preview
    ptk/EffectEvent
    (effect [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            shape (get objects shape-id)

            all-children
            (->> (cfh/selected-with-children objects [shape-id])
                 (ctst/sort-z-index objects)
                 (keep (d/getf objects)))

            fonts (shapes->fonts all-children)]

        (->> (rx/from fonts)
             (rx/merge-map fonts/fetch-font-css)
             (rx/reduce conj [])
             (rx/map #(str/join "\n" %))
             (rx/subs!
              (fn [fontfaces-css]
                (let [style-code
                      (dm/str
                       fontfaces-css "\n"
                       (-> (cg/generate-style-code objects style-type [shape] all-children)
                           (cb/format-code style-type)))

                      markup-code
                      (cg/generate-formatted-markup-code objects markup-type [shape])]

                  (update-preview-window
                   preview
                   (str/format page-template style-code markup-code)
                   (-> shape :selrect :width)
                   (-> shape :selrect :height))))))))))

(defn open-preview-selected
  []
  (ptk/reify ::open-preview-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [shape-id (first (dsh/lookup-selected state))
            closed-preview (rx/subject)
            preview (.open js/window "/#/frame-preview")
            listener-fn #(rx/push! closed-preview true)]
        (when (some? preview)
          (.addEventListener preview "beforeunload" listener-fn))
        (->> (rx/from-atom (refs/all-children-objects shape-id) {:emit-current-value? true})
             (rx/take-until closed-preview)
             (rx/debounce 1000)
             (rx/map #(update-preview preview shape-id)))))))
