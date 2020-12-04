;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.pages.common
  (:require
   [app.common.uuid :as uuid]))

(def file-version 5)
(def default-color "#b1b2b5") ;; $color-gray-20
(def root uuid/zero)

(def component-sync-attrs
  {:fill-color            :fill-group
   :fill-opacity          :fill-group
   :fill-color-gradient   :fill-group
   :fill-color-ref-file   :fill-group
   :fill-color-ref-id     :fill-group
   :content               :content-group
   :font-family           :text-font-group
   :font-size             :text-font-group
   :font-style            :text-font-group
   :font-weight           :text-font-group
   :letter-spacing        :text-display-group
   :line-height           :text-display-group
   :text-align            :text-display-group
   :stroke-color          :stroke-group
   :stroke-color-gradient :stroke-group
   :stroke-color-ref-file :stroke-group
   :stroke-color-ref-id   :stroke-group
   :stroke-opacity        :stroke-group
   :stroke-style          :stroke-group
   :stroke-width          :stroke-group
   :stroke-alignment      :stroke-group
   :rx                    :radius-group
   :ry                    :radius-group
   :selrect               :geometry-group
   :points                :geometry-group
   :locked                :geometry-group
   :proportion            :geometry-group
   :proportion-lock       :geometry-group
   :x                     :geometry-group
   :y                     :geometry-group
   :width                 :geometry-group
   :height                :geometry-group
   :transform             :geometry-group
   :transform-inverse     :geometry-group
   :shadow                :shadow-group
   :blur                  :blur-group
   :masked-group?         :mask-group})

