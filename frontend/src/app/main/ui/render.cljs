;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.render
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [rumext.alpha :as mf]
   [app.common.uuid :as uuid]
   [app.common.pages :as cp]
   [app.common.pages-helpers :as cph]
   [app.common.math :as mth]
   [app.common.geom.shapes :as geom]
   [app.common.geom.point :as gpt]
   [app.common.geom.matrix :as gmt]
   [app.main.exports :as exports]
   [app.main.repo :as repo]))

(mf/defc object-svg
  {::mf/wrap [mf/memo]}
  [{:keys [objects object-id zoom] :or {zoom 1} :as props}]
  (let [object   (get objects object-id)
        frame-id (if (= :frame (:type object))
                   (:id object)
                   (:frame-id object))

        modifier (-> (gpt/point (:x object) (:y object))
                     (gpt/negate)
                     (gmt/translate-matrix))

        mod-ids  (cons frame-id (cph/get-children frame-id objects))
        updt-fn  #(-> %1
                      (assoc-in [%2 :modifiers :displacement] modifier)
                      (update %2 geom/transform-shape))

        objects  (reduce updt-fn objects mod-ids)
        object   (get objects object-id)

        width    (* (get-in object [:selrect :width]) zoom)
        height   (* (get-in object [:selrect :height]) zoom)

        vbox     (str (get-in object [:selrect :x]) " "
                      (get-in object [:selrect :y]) " "
                      (get-in object [:selrect :width]) " "
                      (get-in object [:selrect :height]))

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

    [:svg {:id "screenshot"
           :view-box vbox
           :width width
           :height height
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     (case (:type object)
       :frame [:& frame-wrapper {:shape object :view-box vbox}]
       :group [:& group-wrapper {:shape object}]
       [:& shape-wrapper {:shape object}])]))

(defn- adapt-root-frame
  [objects object-id]
  (if (uuid/zero? object-id)
    (let [object   (get objects object-id)
          shapes   (cph/select-toplevel-shapes objects {:include-frames? true})
          srect    (geom/selection-rect shapes)
          object   (merge object (select-keys srect [:x :y :width :height]))
          object   (geom/transform-shape object)
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
     #(let [subs (->> (repo/query! :file {:id file-id})
                      (rx/subs (fn [{:keys [data]}]
                                 (let [objs (get-in data [:pages-index page-id :objects])
                                       objs (adapt-root-frame objs object-id)]
                                   (reset! objects objs)))))]
        (fn [] (rx/dispose! subs))))

    (when @objects
      [:& object-svg {:objects @objects
                      :object-id object-id
                      :zoom 1}])))
