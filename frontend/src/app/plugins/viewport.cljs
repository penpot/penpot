;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.viewport
  (:require
   [app.common.data.macros :as dm]
   [app.common.spec :as us]
   [app.main.data.workspace.viewport :as dwv]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.store :as st]
   [app.plugins.format :as format]
   [app.plugins.shape :as ps]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(defn viewport-proxy? [p]
  (obj/type-of? p "ViewportProxy"))

(defn viewport-proxy
  [plugin-id]
  (obj/reify {:name "ViewportProxy"}
    :$plugin {:enumerable false :get (fn [] plugin-id)}

    :center
    {:get
     (fn []
       (let [vp (dm/get-in @st/state [:workspace-local :vbox])
             x (+ (:x vp) (/ (:width vp) 2))
             y (+ (:y vp) (/ (:height vp) 2))]
         (.freeze js/Object #js {:x x :y y})))

     :set
     (fn [value]
       (let [new-x (obj/get value "x")
             new-y (obj/get value "y")]
         (cond
           (not (us/safe-number? new-x))
           (u/display-not-valid :center-x new-x)

           (not (us/safe-number? new-y))
           (u/display-not-valid :center-y new-y)

           :else
           (let [vb (dm/get-in @st/state [:workspace-local :vbox])
                 old-x (+ (:x vb) (/ (:width vb) 2))
                 old-y (+ (:y vb) (/ (:height vb) 2))
                 delta-x (- new-x old-x)
                 delta-y (- new-y old-y)
                 to-position
                 {:x #(+ % delta-x)
                  :y #(+ % delta-y)}]
             (st/emit! (dwv/update-viewport-position to-position))))))}

    :zoom
    {:get
     (fn []
       (dm/get-in @st/state [:workspace-local :zoom]))

     :set
     (fn [value]
       (cond
         (not (us/safe-number? value))
         (u/display-not-valid :zoom value)

         :else
         (let [z (dm/get-in @st/state [:workspace-local :zoom])]
           (st/emit! (dwz/set-zoom (/ value z))))))}

    :bounds
    {:get
     (fn []
       (let [vbox (dm/get-in @st/state [:workspace-local :vbox])]
         (.freeze js/Object (format/format-bounds vbox))))}

    :zoomReset
    (fn []
      (st/emit! dwz/reset-zoom))

    :zoomToFitAll
    (fn []
      (st/emit! dwz/zoom-to-fit-all))

    :zoomIntoView
    (fn [shapes]
      (cond
        (not (every? ps/shape-proxy? shapes))
        (u/display-not-valid :zoomIntoView "Argument should be valid shapes")

        :else
        (let [ids (->> shapes
                       (map (fn [shape] (obj/get shape "$id"))))]
          (st/emit! (dwz/fit-to-shapes ids)))))))


