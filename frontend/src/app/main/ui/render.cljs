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
   [app.common.pages.helpers :as cph]
   [app.common.uuid :as uuid]
   [app.main.data.fonts :as df]
   [app.main.render :as render]
   [app.main.repo :as repo]
   [app.main.store :as st]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.util.dom :as dom]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(defn calc-bounds
  [object objects]
  (let [xf-get-bounds (comp (map #(get objects %)) (map #(calc-bounds % objects)))
        padding       (filters/calculate-padding object)
        obj-bounds    (-> (filters/get-filters-bounds object)
                          (update :x - padding)
                          (update :y - padding)
                          (update :width + (* 2 padding))
                          (update :height + (* 2 padding)))]

    (cond
      (and (= :group (:type object))
           (:masked-group? object))
      (calc-bounds (get objects (first (:shapes object))) objects)

      (= :group (:type object))
      (->> (:shapes object)
           (into [obj-bounds] xf-get-bounds)
           (gsh/join-rects))

      :else
      obj-bounds)))

(mf/defc object-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects object-id zoom render-texts?] :or {zoom 1} :as props}]
  (let [object   (get objects object-id)
        frame-id (if (= :frame (:type object))
                   (:id object)
                   (:frame-id object))

        modifier (-> (gpt/point (:x object) (:y object))
                     (gpt/negate)
                     (gmt/translate-matrix))

        mod-ids  (cons frame-id (cph/get-children-ids objects frame-id))
        updt-fn  #(-> %1
                      (assoc-in [%2 :modifiers :displacement] modifier)
                      (update %2 gsh/transform-shape))

        objects  (reduce updt-fn objects mod-ids)
        object   (get objects object-id)

        object (cond-> object
                 (:hide-fill-on-export object)
                 (assoc :fills []))

        {:keys [x y width height] :as bs} (calc-bounds object objects)
        [_ _ width height :as coords] (->> [x y width height] (map #(* % zoom)))

        vbox (str/join " " coords)

        frame-wrapper
        (mf/with-memo [objects]
          (render/frame-wrapper-factory objects))

        group-wrapper
        (mf/with-memo [objects]
          (render/group-wrapper-factory objects))

        shape-wrapper
        (mf/with-memo [objects]
          (render/shape-wrapper-factory objects))

        text-shapes
        (->> objects
             (filter (fn [[_ shape]] (= :text (:type shape))))
             (mapv second))]

    (mf/with-effect [width height]
      (dom/set-page-style {:size (str (mth/ceil width) "px "
                                      (mth/ceil height) "px")}))

    [:& (mf/provider embed/context) {:value false}
     [:svg {:id "screenshot"
            :view-box vbox
            :width width
            :height height
            :version "1.1"
            :xmlns "http://www.w3.org/2000/svg"
            :xmlnsXlink "http://www.w3.org/1999/xlink"
            ;; Fix Chromium bug about color of html texts
            ;; https://bugs.chromium.org/p/chromium/issues/detail?id=1244560#c5
            :style {:-webkit-print-color-adjust :exact}}

      (case (:type object)
        :frame [:& frame-wrapper {:shape object :view-box vbox}]
        :group [:> shape-container {:shape object}
                [:& group-wrapper {:shape object}]]
        [:& shape-wrapper {:shape object}])]

     ;; Auxiliary SVG for rendering text-shapes
     (when render-texts?
       (for [object text-shapes]
         [:& (mf/provider muc/text-plain-colors-ctx) {:value true}
          [:svg {:id (str "screenshot-text-" (:id object))
                 :view-box (str "0 0 " (:width object) " " (:height object))
                 :width (:width object)
                 :height (:height object)
                 :version "1.1"
                 :xmlns "http://www.w3.org/2000/svg"
                 :xmlnsXlink "http://www.w3.org/1999/xlink"}
           [:& shape-wrapper {:shape (assoc object :x 0 :y 0)}]]]))]))

(defn- adapt-root-frame
  [objects object-id]
  (if (uuid/zero? object-id)
    (let [object   (get objects object-id)
          shapes   (cph/get-immediate-children objects)
          srect    (gsh/selection-rect shapes)
          object   (merge object (select-keys srect [:x :y :width :height]))
          object   (gsh/transform-shape object)
          object   (assoc object :fill-color "#f0f0f0")]
      (assoc objects (:id object) object))
    objects))

(mf/defc render-object
  [{:keys [file-id page-id object-id render-texts?] :as props}]
  (let [objects (mf/use-state nil)]

    (mf/with-effect [file-id page-id object-id]
      (->> (rx/zip
            (repo/query! :font-variants {:file-id file-id})
            (repo/query! :trimmed-file {:id file-id :page-id page-id :object-id object-id}))
           (rx/subs
            (fn [[fonts {:keys [data]}]]
              (when (seq fonts)
                (st/emit! (df/fonts-fetched fonts)))
              (let [objs (get-in data [:pages-index page-id :objects])
                    objs (adapt-root-frame objs object-id)]
                (reset! objects objs)))))
      (constantly nil))

    (when @objects
      [:& object-svg {:objects @objects
                      :object-id object-id
                      :render-texts? render-texts?
                      :zoom 1}])))

(mf/defc render-sprite
  [{:keys [file-id component-id] :as props}]
  (let [file (mf/use-state nil)]

    (mf/with-effect [file-id]
      (->> (repo/query! :file {:id file-id})
           (rx/subs
            (fn [result]
              (reset! file result))))
      (constantly nil))

    (when @file
      [:*
       [:& render/components-sprite-svg {:data (:data @file) :embed true}

        (when (some? component-id)
          [:use {:x 0 :y 0
                 :xlinkHref (str "#" component-id)}])]

       (when-not (some? component-id)
         [:ul
          (for [[id data] (get-in @file [:data :components])]
            (let [url (str "#/render-sprite/" (:id @file) "?component-id=" id)]
              [:li [:a {:href url} (:name data)]]))])])))

