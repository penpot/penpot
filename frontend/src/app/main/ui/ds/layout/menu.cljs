;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.layout.menu
  (:require-macros
   [app.main.style :as stl])
  (:require
   ["@penpot/ui/menu" :as menu]
   [app.common.data :as d]
   [rumext.v2 :as mf]))

(def ^:private schema:menu
  [:map
   [:class {:optional true} [:maybe :string]]
   [:is-open {:optional true} [:maybe :boolean]]
   [:on-open-change {:optional true} [:maybe fn?]]
   [:trigger {:optional true} [:maybe :any]]
   [:placement {:optional true}
    [:maybe [:enum "top" "top start" "top end"
             "bottom" "bottom start" "bottom end"
             "left" "right"]]]
   [:on-action {:optional true} [:maybe fn?]]])

(mf/defc menu*
  {::mf/schema schema:menu}
  [{:keys [class is-open on-open-change trigger placement on-action children] :rest props}]
  (let [placement (d/nilv placement "bottom start")
        props
        (mf/spread-props props
                         {:class class
                          :is-open is-open
                          :on-open-change on-open-change
                          :trigger trigger
                          :placement placement
                          :on-action on-action})]
    [:> menu/Menu props
     children]))

(def ^:private schema:menu-item
  [:map
   [:id {:optional true} [:maybe [:or :string :int]]]
   [:class {:optional true} [:maybe :string]]
   [:is-disabled {:optional true} [:maybe :boolean]]
   [:on-action {:optional true} [:maybe fn?]]
   [:text-value {:optional true} [:maybe :string]]])

(mf/defc menu-item*
  {::mf/schema schema:menu-item}
  [{:keys [id class is-disabled on-action text-value children] :rest props}]
  (let [props
        (mf/spread-props props
                         {:id id
                          :class class
                          :is-disabled is-disabled
                          :on-action on-action
                          :text-value text-value})]
    [:> menu/MenuItem props
     children]))

(def ^:private schema:sub-menu
  [:map
   [:id {:optional true} [:maybe [:or :string :int]]]
   [:class {:optional true} [:maybe :string]]
   [:trigger {:optional true} [:maybe :any]]
   [:is-disabled {:optional true} [:maybe :boolean]]
   [:text-value {:optional true} [:maybe :string]]
   [:on-action {:optional true} [:maybe fn?]]])

(mf/defc sub-menu*
  {::mf/schema schema:sub-menu}
  [{:keys [id class trigger is-disabled text-value on-action children] :rest props}]
  (let [props
        (mf/spread-props props
                         {:id id
                          :class class
                          :trigger trigger
                          :is-disabled is-disabled
                          :text-value text-value
                          :on-action on-action})]
    [:> menu/SubMenu props
     children]))

(def ^:private schema:menu-separator
  [:map
   [:class {:optional true} [:maybe :string]]])

(mf/defc menu-separator*
  {::mf/schema schema:menu-separator}
  [{:keys [class] :rest props}]
  (let [props (mf/spread-props props {:class class})]
    [:> menu/MenuSeparator props]))

(def ^:private schema:context-menu
  [:map
   [:class {:optional true} [:maybe :string]]
   [:aria-label :string]
   [:trigger {:optional true} [:maybe :any]]
   [:placement {:optional true}
    [:maybe [:enum "top" "top start" "top end"
             "bottom" "bottom start" "bottom end"
             "left" "right"]]]
   [:is-disabled {:optional true} [:maybe :boolean]]
   [:on-action {:optional true} [:maybe fn?]]])

(mf/defc context-menu*
  {::mf/schema schema:context-menu}
  [{:keys [class aria-label trigger placement is-disabled on-action children] :rest props}]
  (let [placement (d/nilv placement "bottom start")
        props
        (mf/spread-props props
                         {:class class
                          :aria-label aria-label
                          :trigger trigger
                          :placement placement
                          :is-disabled is-disabled
                          :on-action on-action})]
    [:> menu/ContextMenu props
     children]))
