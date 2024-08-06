;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.viewport
  "RPC for plugins runtime."
  (:require
   [app.common.data.macros :as dm]
   [app.common.record :as crc]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.viewport :as dwv]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.store :as st]
   [app.plugins.format :as format]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(deftype ViewportProxy [$plugin]
  Object
  (zoomIntoView [_ shapes]
    (let [ids
          (->> shapes
               (map (fn [v]
                      (if (string? v)
                        (uuid/uuid v)
                        (uuid/uuid (obj/get v "x"))))))]
      (st/emit! (dwz/fit-to-shapes ids)))))

(crc/define-properties!
  ViewportProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "ViewportProxy"))})

(defn viewport-proxy? [p]
  (instance? ViewportProxy p))

(defn viewport-proxy
  [plugin-id]
  (crc/add-properties!
   (ViewportProxy. plugin-id)
   {:name "center"
    :get
    (fn [_]
      (let [vp (dm/get-in @st/state [:workspace-local :vbox])
            x (+ (:x vp) (/ (:width vp) 2))
            y (+ (:y vp) (/ (:height vp) 2))]
        (.freeze js/Object #js {:x x :y y})))

    :set
    (fn [_ value]
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

   {:name "zoom"
    :get
    (fn [_]
      (dm/get-in @st/state [:workspace-local :zoom]))
    :set
    (fn [_ value]
      (cond
        (not (us/safe-number? value))
        (u/display-not-valid :zoom value)

        :else
        (let [z (dm/get-in @st/state [:workspace-local :zoom])]
          (st/emit! (dwz/set-zoom (/ value z))))))}

   {:name "bounds"
    :get
    (fn [_]
      (let [vport (dm/get-in @st/state [:workspace-local :vport])]
        (.freeze js/Object (format/format-bounds vport))))}))


