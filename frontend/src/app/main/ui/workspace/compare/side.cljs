;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.compare.side
  "The side by side view of the compare mode: main on the first half of
  the canvas, the branch on the second one. The branch is the canvas
  itself; main is a picture of each board with changes, drawn off screen
  by the same render and placed over the first half so that both halves
  show the same spot. Both follow the one viewport, so zoom and pan are
  linked; lined up with the change chosen (`:side-mode :aligned`), main
  is moved by how far that layer moved."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.colors :as clr]
   [app.common.data.macros :as dm]
   [app.main.ui.ds.product.branch-pill :refer [branch-pill*]]
   [app.main.ui.workspace.compare.common :as cc]
   [app.main.ui.workspace.review.overlays :as rvo]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.viewport.rulers :as rulers]
   [app.render-wasm.api :as wasm.api]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as ts]
   [app.util.webapi :as wapi]
   [rumext.v2 :as mf]))

(def ^:private max-scale
  "The most a picture of main is enlarged when it is drawn; past it, the
  browser scales the picture."
  4)

(defn picture-roots
  "The ids of the layers of main to take a picture of: the top board of
  each entry of `items` (the layer itself outside any board), as
  `main` (the page as main has it) holds it, or as `objects` (the open
  branch) does for a layer main lacks. Only the ones main has."
  [items main objects]
  (into []
        (comp (filter #(= :shape (:kind %)))
              (keep (fn [{:keys [id]}]
                      (:id (or (brc/top-board main id)
                               (get main id)
                               (brc/top-board objects id)))))
              (filter #(contains? main %))
              (distinct))
        items))

(defn- changed-ids
  [from to]
  (into []
        (filter #(not (identical? (get from %) (get to %))))
        (distinct (concat (keys from) (keys to)))))

(defn- take-pictures
  "Draw each layer of `roots` as `main` has it, off screen, and answer
  `{:id :rect :uri}` for each one that drew. The render holds `canvas`
  (the page as the canvas shows it): main goes in for as long as the
  pictures take and `canvas` comes back before anything reaches the
  screen."
  [canvas main roots scale]
  (when (wasm.api/initialized?)
    (let [ids (changed-ids canvas main)]
      (wasm.api/process-objects (into [] (keep #(get main %)) ids))
      (let [pictures
            (into []
                  (keep (fn [id]
                          (let [rect  (or (wasm.api/get-shape-extrect id)
                                          (dm/get-in main [id :selrect]))
                                bytes (wasm.api/render-shape-pixels id scale :png false)]
                            (when (and (some? rect) (some? bytes) (pos? (.-length ^js bytes)))
                              {:id id
                               :rect (select-keys rect [:x :y :width :height])
                               :uri (wapi/create-uri (wapi/create-blob bytes "image/png"))}))))
                  roots)]
        (wasm.api/process-objects (into [] (keep #(get canvas %)) ids))
        (wasm.api/request-render "compare-side")
        pictures))))

(defn layer-translate
  "Where the layer of main goes, in pixels: the spot of the page the
  second half shows, half a viewport (`half`) back and after the rulers
  (`edge`)."
  [vbox zoom half edge]
  {:x (- (* (- (:x vbox)) zoom) half edge)
   :y (- (* (- (:y vbox)) zoom) edge)})

(defn- use-settled
  "`value`, once it has stayed the same for `ms` milliseconds."
  [value ms]
  (let [settled* (mf/use-state value)]
    (mf/with-effect [value]
      (let [timer (ts/schedule ms #(reset! settled* value))]
        #(ts/dispose! timer)))
    (deref settled*)))

(mf/defc side-view*
  "`objects` is the page as the open branch has it, `canvas` as the
  canvas shows it and `main` as main has it (`cc/main-objects`); `items`
  are the entries of the comparison on the page and `listed` the ones
  its list shows, which are the ones marked. Main is moved by `offset`
  (`cc/align-offset`, nil for none). The rulers are drawn on the canvas,
  so main starts after them (`show-rulers`)."
  [{:keys [comparison objects canvas main offset items listed page-id background vbox vport zoom show-rulers]}]
  (let [{:keys [excluded selected hovered branch mode]} comparison
        half     (/ (:width vport) 2)
        edge     (if show-rulers rulers/ruler-area-size 0)
        place    (layer-translate vbox zoom half edge)
        ;; main slides to the change chosen for a moment after it is
        ;; chosen, and follows pan and zoom at once the rest of the time
        sliding  (not= selected (use-settled selected 250))
        roots    (mf/with-memo [items main objects]
                   (picture-roots items main objects))
        ;; the pictures are drawn again once the zoom rests, sharp at it
        scale    (use-settled (min max-scale (max 1 (* zoom (or (.-devicePixelRatio js/window) 1)))) 250)
        pictures* (mf/use-state [])
        pictures (deref pictures*)
        ;; a layer main lacks leaves a gap where the branch has it
        marks    (mf/with-memo [listed main excluded page-id selected hovered]
                   (cc/chosen-marks
                    selected
                    hovered
                    (brc/change-marks listed {:page-id page-id
                                              :objects main
                                              :excluded excluded
                                              :status-of #(if (contains? main (:id %))
                                                            :present
                                                            :missing)})))]

    (mf/with-effect [canvas main roots scale]
      (let [taken (take-pictures canvas main roots scale)]
        (reset! pictures* (or taken []))
        (fn []
          (run! #(wapi/revoke-uri (:uri %)) taken))))

    [:*
     [:div {:class (stl/css :main-half)
            :style #js {:top (dm/str edge "px")
                        :left (dm/str edge "px")
                        :width (dm/str (- half edge) "px")
                        :backgroundColor (or background clr/canvas)}}
      [:div {:class (stl/css :layer)
             :style #js {:transform (dm/fmt "translate(%px, %px)" (:x place) (:y place))}}
       ;; moved by how far the change chosen moved (`offset`)
       [:div {:class (stl/css-case :layer true :is-sliding sliding)
              :style #js {:transform (dm/fmt "translate(%px, %px)"
                                             (* (:x offset 0) zoom)
                                             (* (:y offset 0) zoom))}}
        (for [{:keys [id rect uri]} pictures]
          [:img {:key (str id)
                 :class (stl/css :picture)
                 :alt ""
                 :src uri
                 :style (rvo/screen-rect rect zoom)}])
        (when-not (:hidden mode)
          (for [mark marks
                :let [missing? (= :missing (:status mark))]]
            [:> rvo/change-box* {:key (:key mark)
                                 :rect (:rect mark)
                                 :zoom zoom
                                 :tone (when missing? "warning")
                                 :class (stl/css-case :is-missing missing?)
                                 :label (cond
                                          missing? (tr "workspace.compare.side.not-in-main")
                                          (= selected (:key mark)) (:label mark)
                                          :else nil)
                                 :is-active (and (not missing?) (= selected (:key mark)))}]))]]
      [:span {:class (stl/css :label)}
       [:> branch-pill* {:label (tr "workspace.branches.main") :is-main true}]]]
     [:span {:class (stl/css :label :is-branch)
             :style #js {:top (dm/str edge "px")}}
      [:> branch-pill* {:label (or (:name branch) "")}]]]))
