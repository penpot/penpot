;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.context-menu
  "A workspace specific context menu (mouse right click)."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.transit :as t]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.page :as ctp]
   [app.common.types.shape.layout :as ctl]
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as scd]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.shape-icon :as sic]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr] :as i18n]
   [app.util.timers :as timers]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def menu-ref
  (l/derived :context-menu refs/workspace-local))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc menu-entry*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [title shortcut on-click on-pointer-enter on-pointer-leave
           on-unmount children is-selected icon disabled value]}]
  (let [submenu-ref (mf/use-ref nil)
        hovering?   (mf/use-ref false)
        on-pointer-enter
        (mf/use-fn
         (fn []
           (mf/set-ref-val! hovering? true)
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (some? submenu-node)
               (dom/set-css-property! submenu-node "display" "block")))
           (when on-pointer-enter (on-pointer-enter))))

        on-pointer-leave
        (mf/use-fn
         (fn []
           (mf/set-ref-val! hovering? false)
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (some? submenu-node)
               (timers/schedule
                200
                #(when-not (mf/ref-val hovering?)
                   (dom/set-css-property! submenu-node "display" "none")))))
           (when on-pointer-leave (on-pointer-leave))))

        set-dom-node
        (mf/use-fn
         (fn [dom]
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (and (some? dom) (some? submenu-node))
               (dom/set-css-property! submenu-node "top" (str (.-offsetTop dom) "px"))))))]

    (mf/use-effect
     (mf/deps on-unmount)
     (constantly on-unmount))

    (if icon
      [:li {:class (stl/css :icon-menu-item)
            :disabled disabled
            :data-value value
            :ref set-dom-node
            :on-click on-click
            :on-pointer-enter on-pointer-enter
            :on-pointer-leave on-pointer-leave}
       [:span
        {:class (stl/css :icon-wrapper)}
        (if is-selected [:span {:class (stl/css :selected-icon)}
                         i/tick]
            [:span {:class (stl/css :selected-icon)}])
        [:span {:class (stl/css :shape-icon)} icon]]
       [:span {:class (stl/css :title)} title]]
      [:li {:class (stl/css :context-menu-item)
            :disabled disabled
            :ref set-dom-node
            :data-value value
            :on-click on-click
            :on-pointer-enter on-pointer-enter
            :on-pointer-leave on-pointer-leave}
       [:span {:class (stl/css :title)} title]
       (when shortcut
         [:span   {:class (stl/css :shortcut)}
          (for [[idx sc] (d/enumerate (scd/split-sc shortcut))]
            [:span {:key (dm/str shortcut "-" idx)
                    :class (stl/css :shortcut-key)} sc])])

       (when (> (count children) 1)
         [:span {:class (stl/css :submenu-icon)} i/arrow])

       (when (> (count children) 1)
         [:ul {:class (stl/css :workspace-context-submenu)
               :ref submenu-ref
               :style {:display "none" :left 250}
               :on-context-menu prevent-default}
          children])])))

(mf/defc menu-separator*
  {::mf/props :obj
   ::mf/private true}
  []
  [:li {:class (stl/css :separator)}])

(mf/defc context-menu-edit*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [shapes]}]
  (let [do-copy           #(st/emit! (dw/copy-selected))
        do-copy-link      #(st/emit! (dw/copy-link-to-clipboard))

        do-cut            #(st/emit! (dw/copy-selected)
                                     (dw/delete-selected))
        do-paste          #(st/emit! (dw/paste-from-clipboard))
        do-duplicate      #(st/emit! (dw/duplicate-selected true))

        enabled-paste-props* (mf/use-state false)

        handle-copy-css
        (mf/use-callback #(st/emit! (dw/copy-selected-css)))

        handle-copy-css-nested
        (mf/use-callback #(st/emit! (dw/copy-selected-css-nested)))

        handle-copy-props
        (mf/use-callback #(st/emit! (dw/copy-selected-props)))

        handle-paste-props
        (mf/use-callback #(st/emit! (dw/paste-selected-props)))

        handle-copy-text
        (mf/use-callback #(st/emit! (dw/copy-selected-text)))

        handle-hover-copy-paste
        (mf/use-callback
         (fn []
           (->> (wapi/read-from-clipboard)
                (rx/take 1)
                (rx/subs!
                 (fn [data]
                   (try
                     (let [pdata (t/decode-str data)]
                       (reset! enabled-paste-props*
                               (and (dw/paste-data-valid? pdata)
                                    (= :copied-props (:type pdata)))))
                     (catch :default _
                       (reset! enabled-paste-props* false))))
                 (fn []
                   (reset! enabled-paste-props* false))))))]

    [:*
     [:> menu-entry* {:title (tr "workspace.shape.menu.copy")
                      :shortcut (sc/get-tooltip :copy)
                      :on-click do-copy}]
     [:> menu-entry* {:title (tr "workspace.shape.menu.copy-link")
                      :shortcut (sc/get-tooltip :copy-link)
                      :on-click do-copy-link}]
     [:> menu-entry* {:title (tr "workspace.shape.menu.cut")
                      :shortcut (sc/get-tooltip :cut)
                      :on-click do-cut}]
     [:> menu-entry* {:title (tr "workspace.shape.menu.paste")
                      :shortcut (sc/get-tooltip :paste)
                      :on-click do-paste}]
     [:> menu-entry* {:title (tr "workspace.shape.menu.duplicate")
                      :shortcut (sc/get-tooltip :duplicate)
                      :on-click do-duplicate}]

     [:> menu-entry* {:title (tr "workspace.shape.menu.copy-paste-as")
                      :on-pointer-enter (when (cf/check-browser? :chrome) handle-hover-copy-paste)}
      [:> menu-entry* {:title (tr "workspace.shape.menu.copy-css")
                       :on-click handle-copy-css}]
      [:> menu-entry* {:title (tr "workspace.shape.menu.copy-css-nested")
                       :on-click handle-copy-css-nested}]

      [:> menu-separator* {}]

      [:> menu-entry* {:title (tr "workspace.shape.menu.copy-text")
                       :on-click handle-copy-text}]

      [:> menu-entry* {:title (tr "workspace.shape.menu.copy-props")
                       :shortcut (sc/get-tooltip :copy-props)
                       :disabled (> (count shapes) 1)
                       :on-click handle-copy-props}]
      [:> menu-entry* {:title (tr "workspace.shape.menu.paste-props")
                       :shortcut (sc/get-tooltip :paste-props)
                       :disabled (and (cf/check-browser? :chrome) (not @enabled-paste-props*))
                       :on-click handle-paste-props}]]

     [:> menu-separator* {}]]))

(mf/defc context-menu-layer-position*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [shapes]}]
  (let [do-bring-forward  (mf/use-fn #(st/emit! (dw/vertical-order-selected :up)))
        do-bring-to-front (mf/use-fn #(st/emit! (dw/vertical-order-selected :top)))
        do-send-backward  (mf/use-fn #(st/emit! (dw/vertical-order-selected :down)))
        do-send-to-back   (mf/use-fn #(st/emit! (dw/vertical-order-selected :bottom)))

        select-shapes     (fn [id] #(st/emit! (dws/select-shape id)))
        on-pointer-enter  (fn [id] #(st/emit! (dw/highlight-shape id)))
        on-pointer-leave  (fn [id] #(st/emit! (dw/dehighlight-shape id)))
        on-unmount        (fn [id] #(st/emit! (dw/dehighlight-shape id)))

        ;; NOTE: we use deref instead of mf/deref on objects because
        ;; we really don't want rerender on object changes
        hover-ids         (deref refs/current-hover-ids)
        objects           (deref refs/workspace-page-objects)
        hover-objs        (into [] (keep (d/getf objects)) hover-ids)]

    [:*
     (when (> (count hover-objs) 1)
       [:> menu-entry* {:title (tr "workspace.shape.menu.select-layer")}
        (for [object hover-objs]
          [:> menu-entry* {:title (:name object)
                           :key (dm/str (:id object))
                           :is-selected (some #(= object %) shapes)
                           :on-click (select-shapes (:id object))
                           :on-pointer-enter (on-pointer-enter (:id object))
                           :on-pointer-leave (on-pointer-leave (:id object))
                           :on-unmount (on-unmount (:id object))
                           :icon (sic/element-icon {:shape object})}])])
     [:> menu-entry* {:title (tr "workspace.shape.menu.forward")
                      :shortcut (sc/get-tooltip :bring-forward)
                      :on-click do-bring-forward}]
     [:> menu-entry* {:title (tr "workspace.shape.menu.front")
                      :shortcut (sc/get-tooltip :bring-front)
                      :on-click do-bring-to-front}]
     [:> menu-entry* {:title (tr "workspace.shape.menu.backward")
                      :shortcut (sc/get-tooltip :bring-backward)
                      :on-click do-send-backward}]
     [:> menu-entry* {:title (tr "workspace.shape.menu.back")
                      :shortcut (sc/get-tooltip :bring-back)
                      :on-click do-send-to-back}]

     [:> menu-separator* {}]]))

(mf/defc context-menu-flip*
  {::mf/props :obj
   ::mf/private true}
  []
  (let [do-flip-vertical #(st/emit! (dw/flip-vertical-selected))
        do-flip-horizontal #(st/emit! (dw/flip-horizontal-selected))]
    [:*
     [:> menu-entry* {:title (tr "workspace.shape.menu.flip-vertical")
                      :shortcut (sc/get-tooltip :flip-vertical)
                      :on-click do-flip-vertical}]

     [:> menu-entry* {:title (tr "workspace.shape.menu.flip-horizontal")
                      :shortcut (sc/get-tooltip :flip-horizontal)
                      :on-click do-flip-horizontal}]
     [:> menu-separator* {}]]))

(mf/defc context-menu-thumbnail*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [shapes]}]
  (let [single?    (= (count shapes) 1)
        has-frame? (some cfh/frame-shape? shapes)
        do-toggle-thumbnail #(st/emit! (dw/toggle-file-thumbnail-selected))]
    (when (and single? has-frame?)
      [:*
       (if (every? :use-for-thumbnail shapes)
         [:> menu-entry* {:title (tr "workspace.shape.menu.thumbnail-remove")
                          :on-click do-toggle-thumbnail}]
         [:> menu-entry* {:title (tr "workspace.shape.menu.thumbnail-set")
                          :shortcut (sc/get-tooltip :thumbnail-set)
                          :on-click do-toggle-thumbnail}])
       [:> menu-separator* {}]])))

(mf/defc context-menu-rename*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [shapes]}]
  (let [do-rename #(st/emit! (dw/start-rename-selected))]
    (when (= (count shapes) 1)
      [:*
       [:> menu-separator* {}]
       [:> menu-entry* {:title (tr "workspace.shape.menu.rename")
                        :shortcut (sc/get-tooltip :rename)
                        :on-click do-rename}]])))

(mf/defc context-menu-group*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [shapes]}]
  (let [multiple?    (> (count shapes) 1)
        single?      (= (count shapes) 1)

        objects      (deref refs/workspace-page-objects)
        any-in-copy? (some true? (map #(ctn/has-any-copy-parent? objects %) shapes))

        ;; components can't be ungrouped
        has-frame? (->> shapes (d/seek #(and (cfh/frame-shape? %) (not (ctk/instance-head? %)))))
        has-group? (->> shapes (d/seek #(and (cfh/group-shape? %) (not (ctk/instance-head? %)))))
        has-bool?  (->> shapes (d/seek cfh/bool-shape?))
        has-mask?  (->> shapes (d/seek :masked-group))

        is-group?  (and single? has-group?)
        is-bool?   (and single? has-bool?)

        do-create-group #(st/emit! (dw/group-selected))
        do-remove-group #(st/emit! (dw/ungroup-selected))
        do-mask-group   #(st/emit! (dw/mask-group))
        do-unmask-group #(st/emit! (dw/unmask-group))
        do-create-artboard-from-selection
        #(st/emit! (dwsh/create-artboard-from-selection))]

    [:*
     (when (not any-in-copy?)
       [:*
        (when (or has-bool? has-group? has-mask? has-frame?)
          [:> menu-entry* {:title (tr "workspace.shape.menu.ungroup")
                           :shortcut (sc/get-tooltip :ungroup)
                           :on-click do-remove-group}])

        [:> menu-entry* {:title (tr "workspace.shape.menu.group")
                         :shortcut (sc/get-tooltip :group)
                         :on-click do-create-group}]

        (when (or multiple? (and is-group? (not has-mask?)) is-bool?)
          [:> menu-entry* {:title (tr "workspace.shape.menu.mask")
                           :shortcut (sc/get-tooltip :mask)
                           :on-click do-mask-group}])

        (when has-mask?
          [:> menu-entry* {:title (tr "workspace.shape.menu.unmask")
                           :shortcut (sc/get-tooltip :unmask)
                           :on-click do-unmask-group}])

        [:> menu-entry* {:title (tr "workspace.shape.menu.create-artboard-from-selection")
                         :shortcut (sc/get-tooltip :artboard-selection)
                         :on-click do-create-artboard-from-selection}]
        [:> menu-separator* {}]])]))

(mf/defc context-focus-mode-menu*
  {::mf/props :obj
   ::mf/private true}
  []
  (let [focus (mf/deref refs/workspace-focus-selected)
        do-toggle-focus-mode #(st/emit! (dw/toggle-focus-mode))]

    [:> menu-entry* {:title (if (empty? focus)
                              (tr "workspace.focus.focus-on")
                              (tr "workspace.focus.focus-off"))
                     :shortcut (sc/get-tooltip :toggle-focus-mode)
                     :on-click do-toggle-focus-mode}]))

(mf/defc context-menu-path*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [shapes disable-flatten disable-booleans]}]
  (let [multiple?            (> (count shapes) 1)
        single?              (= (count shapes) 1)

        has-group?           (->> shapes (d/seek cfh/group-shape?))
        has-bool?            (->> shapes (d/seek cfh/bool-shape?))
        has-frame?           (->> shapes (d/seek cfh/frame-shape?))
        has-path?            (->> shapes (d/seek cfh/path-shape?))

        is-group?            (and single? has-group?)
        is-bool?             (and single? has-bool?)
        is-frame?            (and single? has-frame?)

        do-start-editing     (fn [] (timers/schedule #(st/emit! (dw/start-editing-selected))))
        do-transform-to-path #(st/emit! (dw/convert-selected-to-path))

        make-do-bool
        (fn [bool-type]
          #(cond
             multiple?
             (st/emit! (dw/create-bool bool-type))

             is-group?
             (st/emit! (dw/group-to-bool (-> shapes first :id) bool-type))

             is-bool?
             (st/emit! (dw/change-bool-type (-> shapes first :id) bool-type))))]
    [:*
     (when (and single? (not is-frame?))
       [:> menu-entry* {:title (tr "workspace.shape.menu.edit")
                        :shortcut (sc/get-tooltip :start-editing)
                        :on-click do-start-editing}])

     (when-not (or disable-flatten has-frame? has-path?)
       [:> menu-entry* {:title (tr "workspace.shape.menu.transform-to-path")
                        :on-click do-transform-to-path}])

     (when (and (not disable-booleans)
                (or multiple? (and single? (or is-group? is-bool?))))
       [:> menu-entry* {:title (tr "workspace.shape.menu.path")}
        [:> menu-entry* {:title (tr "workspace.shape.menu.union")
                         :shortcut (sc/get-tooltip :bool-union)
                         :on-click (make-do-bool :union)}]
        [:> menu-entry* {:title (tr "workspace.shape.menu.difference")
                         :shortcut (sc/get-tooltip :bool-difference)
                         :on-click (make-do-bool :difference)}]
        [:> menu-entry* {:title (tr "workspace.shape.menu.intersection")
                         :shortcut (sc/get-tooltip :bool-intersection)
                         :on-click (make-do-bool :intersection)}]
        [:> menu-entry* {:title (tr "workspace.shape.menu.exclude")
                         :shortcut (sc/get-tooltip :bool-exclude)
                         :on-click (make-do-bool :exclude)}]

        (when (and single? is-bool? (not disable-flatten))
          [:*
           [:> menu-separator* {}]
           [:> menu-entry* {:title (tr "workspace.shape.menu.flatten")
                            :on-click do-transform-to-path}]])])]))

(mf/defc context-menu-layer-options*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [shapes]}]
  (let [ids (mapv :id shapes)
        do-show-shape #(st/emit! (dw/update-shape-flags ids {:hidden false}))
        do-hide-shape #(st/emit! (dw/update-shape-flags ids {:hidden true}))
        do-lock-shape #(st/emit! (dw/update-shape-flags ids {:blocked true}))
        do-unlock-shape #(st/emit! (dw/update-shape-flags ids {:blocked false}))]
    [:*
     (if (every? :hidden shapes)
       [:> menu-entry* {:title (tr "workspace.shape.menu.show")
                        :shortcut (sc/get-tooltip :toggle-visibility)
                        :on-click do-show-shape}]
       [:> menu-entry* {:title (tr "workspace.shape.menu.hide")
                        :shortcut (sc/get-tooltip :toggle-visibility)
                        :on-click do-hide-shape}])

     (if (every? :blocked shapes)
       [:> menu-entry* {:title (tr "workspace.shape.menu.unlock")
                        :shortcut (sc/get-tooltip :toggle-lock)
                        :on-click do-unlock-shape}]
       [:> menu-entry* {:title (tr "workspace.shape.menu.lock")
                        :shortcut (sc/get-tooltip :toggle-lock)
                        :on-click do-lock-shape}])]))

(mf/defc context-menu-prototype*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [shapes]}]
  (let [flows           (mf/deref refs/workspace-page-flows)
        options-mode    (mf/deref refs/options-mode-global)
        do-add-flow     #(st/emit! (dwi/add-flow-selected-frame))
        do-remove-flow  #(st/emit! (dwi/remove-flow (:id %)))

        prototype?      (= options-mode :prototype)
        single?         (= (count shapes) 1)

        has-frame?      (d/seek cfh/frame-shape? shapes)
        is-frame?       (and single? has-frame?)]

    (when (and prototype? is-frame?)
      (if-let [flow (ctp/get-frame-flow flows (-> shapes first :id))]
        [:> menu-entry* {:title (tr "workspace.shape.menu.delete-flow-start")
                         :on-click (do-remove-flow flow)}]
        [:> menu-entry* {:title (tr "workspace.shape.menu.flow-start")
                         :on-click do-add-flow}]))))

(mf/defc context-menu-layout*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [shapes]}]
  (let [single?      (= (count shapes) 1)
        objects      (deref refs/workspace-page-objects)
        any-in-copy? (some true? (map #(ctn/has-any-copy-parent? objects %) shapes))

        has-flex?
        (and single? (every? ctl/flex-layout? shapes))

        has-grid?
        (and single? (every? ctl/grid-layout? shapes))

        on-add-layout
        (mf/use-fn
         (fn [event]
           (let [type (-> (dom/get-current-target event)
                          (dom/get-data "value")
                          (keyword))]
             (st/emit! (with-meta (dwsl/create-layout type)
                         {::ev/origin "workspace:context-menu"})))))

        on-remove-layout
        (mf/use-fn
         (mf/deps shapes)
         (fn [_event]
           (let [ids (map :id shapes)]
             (st/emit! (dwsl/remove-layout ids)))))]
    [:*
     (when (not any-in-copy?)
       (if (or ^boolean has-flex?
               ^boolean has-grid?)
         [:div
          [:> menu-separator* {}]
          (if has-flex?
            [:> menu-entry* {:title (tr "workspace.shape.menu.remove-flex")
                             :shortcut (sc/get-tooltip :toggle-layout-flex)
                             :on-click on-remove-layout}]
            [:> menu-entry* {:title (tr "workspace.shape.menu.remove-grid")
                             :shortcut (sc/get-tooltip :toggle-layout-grid)
                             :on-click on-remove-layout}])]

         [:div
          [:> menu-separator* {}]
          [:> menu-entry* {:title (tr "workspace.shape.menu.add-flex")
                           :shortcut (sc/get-tooltip :toggle-layout-flex)
                           :value "flex"
                           :on-click on-add-layout}]
          [:> menu-entry* {:title (tr "workspace.shape.menu.add-grid")
                           :shortcut (sc/get-tooltip :toggle-layout-grid)
                           :value "grid"
                           :on-click on-add-layout}]]))]))

(mf/defc context-menu-component*
  {:mf/private true}
  [{:keys [shapes]}]
  (let [single?                    (= (count shapes) 1)
        objects                    (deref refs/workspace-page-objects)
        can-make-component         (every? true? (map #(ctn/valid-shape-for-component? objects %) shapes))
        heads                      (filter ctk/instance-head? shapes)
        components-menu-entries    (cmm/generate-components-menu-entries heads true)
        do-add-component           #(st/emit! (dwl/add-component))
        do-add-multiple-components #(st/emit! (dwl/add-multiple-components))]
    [:*
     (when can-make-component ;; We don't want to change the structure of component copies
       [:*
        [:> menu-separator* {}]

        [:> menu-entry* {:title (tr "workspace.shape.menu.create-component")
                         :shortcut (sc/get-tooltip :create-component)
                         :on-click do-add-component}]
        (when (not single?)
          [:> menu-entry* {:title (tr "workspace.shape.menu.create-multiple-components")
                           :on-click do-add-multiple-components}])])

     (when (seq components-menu-entries)
       [:*
        [:> menu-separator*]
        (for [entry (filter some? components-menu-entries)]
          [:> menu-entry* {:key (:title entry)
                           :title (:title entry)
                           :shortcut (when (contains? entry :shortcut)
                                       (sc/get-tooltip (:shortcut entry)))
                           :on-click (:action entry)}])])]))

(mf/defc context-menu-delete*
  {::mf/props :obj
   ::mf/private true}
  []
  (let [do-delete #(st/emit! (dw/delete-selected))]
    [:*
     [:> menu-separator* {}]
     [:> menu-entry* {:title (tr "workspace.shape.menu.delete")
                      :shortcut (sc/get-tooltip :delete)
                      :on-click do-delete}]]))

(mf/defc shape-context-menu*
  {::mf/wrap [mf/memo]
   ::mf/private true
   ::mf/props :obj}
  [{:keys [mdata]}]
  (let [{:keys [disable-booleans disable-flatten]} mdata
        shapes (mf/deref refs/selected-objects)
        props  (mf/props
                {:shapes shapes
                 :disable-booleans disable-booleans
                 :disable-flatten disable-flatten})]
    (when-not (empty? shapes)
      [:*
       [:> context-menu-edit* props]
       [:> context-menu-layer-position* props]
       [:> context-menu-flip* props]
       [:> context-menu-thumbnail* props]
       [:> context-menu-rename* props]
       [:> context-menu-group* props]
       [:> context-focus-mode-menu* props]
       [:> context-menu-path* props]
       [:> context-menu-layer-options* props]
       [:> context-menu-prototype* props]
       [:> context-menu-layout* props]
       [:> context-menu-component* props]
       [:> context-menu-delete* props]])))

(mf/defc page-item-context-menu*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [mdata]}]
  (let [page (:page mdata)
        deletable? (:deletable? mdata)
        id (:id page)
        delete-fn #(st/emit! (dw/delete-page id))
        do-delete #(st/emit! (modal/show
                              {:type :confirm
                               :title (tr "modals.delete-page.title")
                               :message (tr "modals.delete-page.body")
                               :on-accept delete-fn}))
        do-duplicate #(st/emit!
                       (dw/duplicate-page id)
                       (ptk/event ::ev/event {::ev/name "duplicate-page"}))
        do-rename #(st/emit! (dw/start-rename-page-item id))]

    [:*
     (when deletable?
       [:> menu-entry* {:title (tr "workspace.assets.delete")
                        :on-click do-delete}])

     [:> menu-entry* {:title (tr "workspace.assets.rename")
                      :on-click do-rename}]
     [:> menu-entry* {:title (tr "workspace.assets.duplicate")
                      :on-click do-duplicate}]]))

(mf/defc viewport-context-menu*
  {::mf/props :obj}
  []
  (let [focus      (mf/deref refs/workspace-focus-selected)
        read-only? (mf/use-ctx ctx/workspace-read-only?)
        do-paste   #(st/emit! (dw/paste-from-clipboard))
        do-hide-ui #(st/emit! (-> (dw/toggle-layout-flag :hide-ui)
                                  (vary-meta assoc ::ev/origin "workspace-context-menu")))
        do-toggle-focus-mode #(st/emit! (dw/toggle-focus-mode))]
    [:*
     (when-not ^boolean read-only?
       [:> menu-entry* {:title (tr "workspace.shape.menu.paste")
                        :shortcut (sc/get-tooltip :paste)
                        :on-click do-paste}])
     [:> menu-entry* {:title (tr "workspace.shape.menu.hide-ui")
                      :shortcut (sc/get-tooltip :hide-ui)
                      :on-click do-hide-ui}]

     (when (d/not-empty? focus)
       [:> menu-entry* {:title (tr "workspace.focus.focus-off")
                        :shortcut (sc/get-tooltip :toggle-focus-mode)
                        :on-click do-toggle-focus-mode}])]))

(mf/defc grid-track-context-menu*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [mdata]}]
  (let [{:keys [type index grid-id]} mdata
        do-delete-track
        (mf/use-fn
         (mf/deps grid-id type index)
         (fn []
           (st/emit! (dwsl/remove-layout-track [grid-id] type index))))

        do-add-track-before
        (mf/use-fn
         (mf/deps grid-id type index)
         (fn []
           (st/emit! (dwsl/add-layout-track [grid-id] type ctl/default-track-value index))))

        do-add-track-after
        (mf/use-fn
         (mf/deps grid-id type index)
         (fn []
           (st/emit! (dwsl/add-layout-track [grid-id] type ctl/default-track-value (inc index)))))

        do-duplicate-track
        (mf/use-fn
         (mf/deps grid-id type index)
         (fn []
           (st/emit! (dwsl/duplicate-layout-track [grid-id] type index))))

        do-delete-track-shapes
        (mf/use-fn
         (mf/deps grid-id type index)
         (fn []
           (st/emit! (dwsl/remove-layout-track [grid-id] type index {:with-shapes? true}))))]

    (if (= type :column)
      [:*
       [:> menu-entry* {:title (tr "workspace.context-menu.grid-track.column.duplicate") :on-click do-duplicate-track}]
       [:> menu-entry* {:title (tr "workspace.context-menu.grid-track.column.add-before") :on-click do-add-track-before}]
       [:> menu-entry* {:title (tr "workspace.context-menu.grid-track.column.add-after") :on-click do-add-track-after}]
       [:> menu-entry* {:title (tr "workspace.context-menu.grid-track.column.delete") :on-click do-delete-track}]
       [:> menu-entry* {:title (tr "workspace.context-menu.grid-track.column.delete-shapes") :on-click do-delete-track-shapes}]]

      [:*
       [:> menu-entry* {:title (tr "workspace.context-menu.grid-track.row.duplicate") :on-click do-duplicate-track}]
       [:> menu-entry* {:title (tr "workspace.context-menu.grid-track.row.add-before") :on-click do-add-track-before}]
       [:> menu-entry* {:title (tr "workspace.context-menu.grid-track.row.add-after") :on-click do-add-track-after}]
       [:> menu-entry* {:title (tr "workspace.context-menu.grid-track.row.delete") :on-click do-delete-track}]
       [:> menu-entry* {:title (tr "workspace.context-menu.grid-track.row.delete-shapes") :on-click do-delete-track-shapes}]])))

(mf/defc grid-cells-context-menu*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [mdata]}]
  (let [{:keys [grid cells]} mdata

        single? (= (count cells) 1)

        can-merge?
        (mf/use-memo
         (mf/deps cells)
         #(ctl/valid-area-cells? cells))

        do-merge-cells
        (mf/use-fn
         (mf/deps grid cells)
         (fn []
           (st/emit! (dwsl/merge-cells (:id grid) (map :id cells)))))

        do-create-board
        (mf/use-fn
         (mf/deps grid cells)
         (fn []
           (st/emit! (dwsl/create-cell-board (:id grid) (map :id cells)))))]
    [:*
     (when (not single?)
       [:> menu-entry* {:title (tr "workspace.context-menu.grid-cells.merge")
                        :on-click do-merge-cells
                        :disabled (not can-merge?)}])

     (when single?
       [:> menu-entry* {:title (tr "workspace.context-menu.grid-cells.area")
                        :on-click do-merge-cells}])

     [:> menu-entry* {:title (tr "workspace.context-menu.grid-cells.create-board")
                      :on-click do-create-board
                      :disabled (and (not single?) (not can-merge?))}]]))


;; FIXME: optimize because it is rendered always

(mf/defc context-menu*
  []
  (let [mdata        (mf/deref menu-ref)
        top          (- (get-in mdata [:position :y]) 20)
        left         (get-in mdata [:position :x])
        dropdown-ref (mf/use-ref)
        read-only?   (mf/use-ctx ctx/workspace-read-only?)]

    (mf/with-effect [mdata]
      (when-let [dropdown (mf/ref-val dropdown-ref)]
        (let [bounding-rect (dom/get-bounding-rect dropdown)
              window-size (dom/get-window-size)
              delta-x (max (- (+ (:right bounding-rect) 250) (:width window-size)) 0)
              delta-y (max (- (:bottom bounding-rect) (:height window-size)) 0)
              new-style (str "top: " (- top delta-y) "px; "
                             "left: " (- left delta-x) "px;")]
          (when (or (> delta-x 0) (> delta-y 0))
            (.setAttribute ^js dropdown "style" new-style)))))

    [:& dropdown {:show (boolean mdata)
                  :on-close #(st/emit! dw/hide-context-menu)}
     [:div {:class (stl/css :workspace-context-menu)
            :ref dropdown-ref
            :style {:top top :left left}
            :on-context-menu prevent-default}

      [:ul {:class (stl/css :context-list)}
       (if ^boolean read-only?
         [:> viewport-context-menu* {:mdata mdata}]
         (case (:kind mdata)
           :shape [:> shape-context-menu* {:mdata mdata}]
           :page [:> page-item-context-menu* {:mdata mdata}]
           :grid-track [:> grid-track-context-menu* {:mdata mdata}]
           :grid-cells [:> grid-cells-context-menu* {:mdata mdata}]
           [:> viewport-context-menu* {:mdata mdata}]))]]]))
