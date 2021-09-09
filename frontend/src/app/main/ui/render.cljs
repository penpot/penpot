;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.render
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.main.data.fonts :as df]
   [app.main.exports :as exports]
   [app.main.repo :as repo]
   [app.main.store :as st]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.export :as ed]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.util.dom :as dom]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(mf/defc object-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects object-id zoom] :or {zoom 1} :as props}]
  (let [object   (get objects object-id)
        frame-id (if (= :frame (:type object))
                   (:id object)
                   (:frame-id object))

        include-metadata? (mf/use-ctx ed/include-metadata-ctx)

        modifier (-> (gpt/point (:x object) (:y object))
                     (gpt/negate)
                     (gmt/translate-matrix))

        mod-ids  (cons frame-id (cp/get-children frame-id objects))
        updt-fn  #(-> %1
                      (assoc-in [%2 :modifiers :displacement] modifier)
                      (update %2 gsh/transform-shape))

        objects  (reduce updt-fn objects mod-ids)
        object   (get objects object-id)

        ;; We need to get the shadows/blurs paddings to create the viewbox properly
        {:keys [x y width height]} (filters/get-filters-bounds object)

        x        (* x zoom)
        y        (* y zoom)
        width    (* width zoom)
        height   (* height zoom)

        padding (* (filters/calculate-padding object) zoom)

        vbox     (str/join " " [(- x padding)
                                (- y padding)
                                (+ width padding padding)
                                (+ height padding padding)])

        frame-wrapper
        (mf/use-memo
         (mf/deps objects)
         #(exports/frame-wrapper-factory objects))

        group-wrapper
        (mf/use-memo
         (mf/deps objects)
         #(exports/group-wrapper-factory objects))

        shape-wrapper
        (mf/use-memo
         (mf/deps objects)
         #(exports/shape-wrapper-factory objects))
        ]

    (mf/use-effect
     (mf/deps width height)
     #(dom/set-page-style {:size (str (mth/ceil (+ width padding padding)) "px "
                                      (mth/ceil (+ height padding padding)) "px")}))

    [:& (mf/provider embed/context) {:value true}
     [:svg {:id "screenshot"
            :view-box vbox
            :width (+ width padding padding)
            :height (+ height padding padding)
            :version "1.1"
            :xmlns "http://www.w3.org/2000/svg"
            :xmlnsXlink "http://www.w3.org/1999/xlink"
            :xmlns:penpot (when include-metadata? "https://penpot.app/xmlns")
            ;; Fix Chromium bug about color of html texts
            ;; https://bugs.chromium.org/p/chromium/issues/detail?id=1244560#c5
            :style {:-webkit-print-color-adjust :exact}}

      (case (:type object)
        :frame [:& frame-wrapper {:shape object :view-box vbox}]
        :group [:> shape-container {:shape object}
                [:& group-wrapper {:shape object}]]
        [:& shape-wrapper {:shape object}])]]))

(defn- adapt-root-frame
  [objects object-id]
  (if (uuid/zero? object-id)
    (let [object   (get objects object-id)
          shapes   (cp/select-toplevel-shapes objects {:include-frames? true})
          srect    (gsh/selection-rect shapes)
          object   (merge object (select-keys srect [:x :y :width :height]))
          object   (gsh/transform-shape object)
          object   (assoc object :fill-color "#f0f0f0")]
      (assoc objects (:id object) object))
    objects))


;; NOTE: for now, it is ok download the entire file for render only
;; single page but in a future we need consider to add a specific
;; backend entry point for download only the data of single page.

(mf/defc render-object
  [{:keys [file-id page-id object-id] :as props}]
  (let [objects (mf/use-state nil)]
    (mf/use-effect
     (mf/deps file-id page-id object-id)
     (fn []
       (->> (rx/zip
             (repo/query! :font-variants {:file-id file-id})
             (repo/query! :file {:id file-id}))
            (rx/subs
             (fn [[fonts {:keys [data]}]]
               (when (seq fonts)
                 (st/emit! (df/fonts-fetched fonts)))
               (let [objs (get-in data [:pages-index page-id :objects])
                     objs (adapt-root-frame objs object-id)]
                 (reset! objects objs)))))
       (constantly nil)))

    (when @objects
      [:& object-svg {:objects @objects
                      :object-id object-id
                      :zoom 1}])))

(mf/defc render-sprite
  [{:keys [file-id component-id] :as props}]
  (let [file (mf/use-state nil)]
    (mf/use-effect
     (mf/deps file-id)
     (fn []
       (->> (repo/query! :file {:id file-id})
            (rx/subs
             (fn [result]
               (reset! file result))))
       (constantly nil)))

    (when @file
      [:*
       [:& exports/components-sprite-svg {:data (:data @file) :embed true}

        (when (some? component-id)
          [:use {:x 0 :y 0
                 :xlinkHref (str "#" component-id)}])]

       (when-not (some? component-id)
         [:ul
          (for [[id data] (get-in @file [:data :components])]
            (let [url (str "#/render-sprite/" (:id @file) "?component-id=" id)]
              [:li [:a {:href url} (:name data)]]))])])))

