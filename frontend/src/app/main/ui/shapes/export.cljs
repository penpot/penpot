;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.export
 (:require
  [app.common.data :as d]
  [app.common.geom.matrix :as gmt]
  [app.util.json :as json]
  [app.util.object :as obj]
  [rumext.alpha :as mf]))

(defn add-data
  "Adds as metadata properties that we cannot deduce from the exported SVG"
  [props shape]
  (let [add!
        (fn [props attr val]
          (let [ns-attr (str "penpot:" (-> attr d/name))]
            (-> props
                (obj/set! ns-attr val))))
        frame? (= :frame (:type shape))
        group? (= :group (:type shape))
        rect?  (= :text (:type shape))
        text?  (= :text (:type shape))
        mask?  (and group? (:masked-group? shape))]
    (-> props
        (add! :name              (-> shape :name))
        (add! :blocked           (-> shape (:blocked false) str))
        (add! :hidden            (-> shape (:hidden false) str))
        (add! :type              (-> shape :type d/name))

        (add! :stroke-style      (-> shape (:stroke-style :none) d/name))
        (add! :stroke-alignment  (-> shape (:stroke-alignment :center) d/name))

        (add! :transform         (-> shape (:transform (gmt/matrix)) str))
        (add! :transform-inverse (-> shape (:transform-inverse (gmt/matrix)) str))

        (cond-> (and rect? (some? (:r1 shape)))
          (-> (add! :r1 (-> shape (:r1 0) str))
              (add! :r2 (-> shape (:r2 0) str))
              (add! :r3 (-> shape (:r3 0) str))
              (add! :r4 (-> shape (:r4 0) str))))

        (cond-> text?
          (-> (add! :grow-type (-> shape :grow-type))
              (add! :content (-> shape :content json/encode))))

        (cond-> mask?
          (add! :masked-group "true")))))

(mf/defc export-data
  [{:keys [shape]}]
  (let [props (-> (obj/new)
                  (add-data shape))]
    [:> "penpot:shape" props
     (for [{:keys [style hidden color offset-x offset-y blur spread]} (:shadow shape)]
       [:> "penpot:shadow" #js {:penpot:shadow-type (d/name style)
                                :penpot:hidden (str hidden)
                                :penpot:color (str (:color color))
                                :penpot:opacity (str (:opacity color))
                                :penpot:offset-x (str offset-x)
                                :penpot:offset-y (str offset-y)
                                :penpot:blur (str blur)
                                :penpot:spread (str spread)}])

     (when (some? (:blur shape))
       (let [{:keys [type hidden value]} (:blur shape)]
         [:> "penpot:blur" #js {:penpot:blur-type (d/name type)
                                :penpot:hidden    (str hidden)
                                :penpot:value     (str value)}]))

     (for [{:keys [scale suffix type]} (:exports shape)]
       [:> "penpot:export" #js {:penpot:type   (d/name type)
                                :penpot:suffix suffix
                                :penpot:scale  (str scale)}])]))

