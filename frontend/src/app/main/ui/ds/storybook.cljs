;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.storybook
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [rumext.v2 :as mf]))

(mf/defc story-grid*
  [{:keys [children size style] :rest other}]
  (let [class (stl/css :story-grid)
        size  (or size 16)
        style (or style #js {})
        style (mf/spread-props style {"--component-grid-size" (dm/str size "px")})
        props (mf/spread-props other {:class class :style style})]
    [:> "article" props children]))

(mf/defc story-grid-cell*
  [{:keys [children] :rest other}]
  (let [class (stl/css :story-grid-cell)
        props (mf/spread-props other {:class class})]
    [:> "article" props children]))

(mf/defc story-header*
  [{:keys [children] :rest other}]
  (let [class (stl/css :story-header)
        props (mf/spread-props other {:class class})]
    [:> "header" props children]))

(mf/defc story-grid-row*
  [{:keys [children] :rest other}]
  (let [class (stl/css :story-grid-row)
        props (mf/spread-props other {:class class})]
    [:> "article" props children]))
