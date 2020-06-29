;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.render
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [rumext.alpha :as mf]
   [uxbox.common.uuid :as uuid]
   [uxbox.common.pages :as cp]
   [uxbox.common.pages-helpers :as cph]
   [uxbox.common.math :as mth]
   [uxbox.common.geom.shapes :as geom]
   [uxbox.common.geom.point :as gpt]
   [uxbox.common.geom.matrix :as gmt]
   [uxbox.main.exports :as exports]
   [uxbox.main.repo :as repo]))

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


(mf/defc render-object
  [{:keys [page-id object-id] :as props}]
  (let [data (mf/use-state nil)]
    (mf/use-effect
     (fn []
       (let [subs (->> (repo/query! :page {:id page-id})
                       (rx/subs (fn [result]
                                  (reset! data (:data result)))))]
         #(rx/dispose! subs))))
    (when @data
      [:& object-svg {:objects (:objects @data)
                      :object-id object-id
                      :zoom 1}])))
