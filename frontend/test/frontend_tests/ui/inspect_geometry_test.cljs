;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.inspect-geometry-test
  (:require
   ["react-dom/server" :as rds]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.inspect.attributes.geometry :as geometry]
   [app.main.ui.inspect.attributes.stroke :as stroke]
   [app.main.ui.inspect.styles.panels.geometry :as styles-geometry]
   [app.main.ui.inspect.styles.panels.stroke :as styles-stroke]
   [app.util.code-gen.style-css :as css]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.mock :as mock]
   [rumext.v2 :as mf]))

(def ^:private per-side-stroke
  {:stroke-style :solid
   :stroke-alignment :inner
   :stroke-color "#000000"
   :stroke-opacity 1
   :stroke-width 2
   :stroke-width-top 2
   :stroke-width-right 4
   :stroke-width-bottom 8
   :stroke-width-left 16})

(defn- shape
  [type stroke]
  {:id (uuid/next)
   :type type
   :parent-id uuid/zero
   :frame-id uuid/zero
   :selrect (grc/make-rect 0 0 120 80)
   :points [(gpt/point 0 0) (gpt/point 120 0)
            (gpt/point 120 80) (gpt/point 0 80)]
   :transform (gmt/matrix)
   :r1 5 :r2 5 :r3 5 :r4 5
   :strokes [stroke]})

(defn- render-panel
  [panel shapes]
  (rds/renderToStaticMarkup
   (mf/element panel
               #js {:shapes shapes
                    :objects (into {uuid/zero {:id uuid/zero
                                               :type :frame
                                               :selrect (grc/make-rect 0 0 120 80)}}
                                   (map (juxt :id identity)) shapes)
                    :resolvedTokens {}
                    :colorSpace "hex"
                    :onGeometryShorthand identity
                    :onStrokeShorthand identity})))

(t/deftest geometry-panels-exclude-stroke-widths
  (doseq [type [:frame :rect]
          panel [geometry/geometry-panel* styles-geometry/geometry-panel*]]
    (t/testing (str "geometry for " (name type))
      (let [markup (render-panel panel [(shape type per-side-stroke)])]
        (t/is (not (re-find #"Border (block|inline).*? width" markup)))
        (t/is (str/includes? markup "120px"))
        (t/is (str/includes? markup "80px"))
        (t/is (str/includes? markup "5px"))))))

(t/deftest geometry-panels-exclude-stroke-widths-in-multiple-selection
  (let [shapes [(shape :frame per-side-stroke) (shape :rect per-side-stroke)]]
    (doseq [panel [geometry/geometry-panel* styles-geometry/geometry-panel*]]
      (t/is (not (re-find #"Border (block|inline).*? width"
                          (render-panel panel shapes)))))))

(t/deftest stroke-panels-keep-per-side-widths
  (with-redefs [cf/flags (conj cf/flags :inspect-styles)
                hooks/use-portal-container (mock/stub (fn [_] nil))]
    (doseq [type [:frame :rect]
            panel [stroke/stroke-panel* styles-stroke/stroke-panel*]]
      (let [markup (render-panel panel [(shape type per-side-stroke)])]
        (doseq [label ["Border block start width" "Border inline end width"
                       "Border block end width" "Border inline start width"]]
          (t/is (str/includes? markup label)))
        (doseq [value ["2px" "4px" "8px" "16px"]]
          (t/is (str/includes? markup value)))))))

(t/deftest code-view-keeps-each-per-side-width-once
  (let [shape (shape :frame per-side-stroke)
        code  (css/get-shape-properties-css {(:id shape) shape} shape css/shape-css-properties)]
    (doseq [declaration ["border-block-start-width: 2px;"
                         "border-inline-end-width: 4px;"
                         "border-block-end-width: 8px;"
                         "border-inline-start-width: 16px;"]]
      (t/is (= 1 (count (re-seq (re-pattern declaration) code)))))))

(t/deftest uniform-strokes-keep-width-in-the-stroke-panel
  (let [uniform-stroke (-> per-side-stroke
                           (dissoc :stroke-width-top :stroke-width-right
                                   :stroke-width-bottom :stroke-width-left)
                           (assoc :stroke-width 3))
        shapes         [(shape :rect uniform-stroke)]]
    (doseq [panel [geometry/geometry-panel* styles-geometry/geometry-panel*]]
      (t/is (not (str/includes? (render-panel panel shapes) "Border width"))))
    (with-redefs [cf/flags (conj cf/flags :inspect-styles)
                  hooks/use-portal-container (mock/stub (fn [_] nil))]
      (doseq [panel [stroke/stroke-panel* styles-stroke/stroke-panel*]]
        (let [markup (render-panel panel shapes)]
          (t/is (str/includes? markup "Border width"))
          (t/is (str/includes? markup "3px")))))))
