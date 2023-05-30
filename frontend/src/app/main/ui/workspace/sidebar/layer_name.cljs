;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.layer-name
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private space-for-icons 110)

(def lens:shape-for-rename
  (-> (l/in [:workspace-local :shape-for-rename])
      (l/derived st/state)))

(mf/defc layer-name
  {::mf/wrap-props false
   ::mf/forward-ref true}
  [{:keys [shape-id shape-name shape-touched? disabled-double-click
           on-start-edit on-stop-edit depth parent-size selected?
           type-comp type-frame hidden?]} external-ref]
  (let [edition*         (mf/use-state false)
        edition?         (deref edition*)

        local-ref        (mf/use-ref)
        ref              (or external-ref local-ref)

        shape-for-rename (mf/deref lens:shape-for-rename)
        new-css-system   (mf/use-ctx ctx/new-css-system)

        start-edit
        (mf/use-fn
         (mf/deps disabled-double-click on-start-edit shape-id)
         (fn []
           (when (not disabled-double-click)
             (on-start-edit)
             (reset! edition* true)
             (st/emit! (dw/start-rename-shape shape-id)))))

        accept-edit
        (mf/use-fn
         (mf/deps on-stop-edit)
         (fn []
           (let [name-input     (mf/ref-val ref)
                 name           (str/trim (dom/get-value name-input))]
             (on-stop-edit)
             (reset! edition* false)
             (st/emit! (dw/end-rename-shape name)))))

        cancel-edit
        (mf/use-fn
         (mf/deps on-stop-edit)
         (fn []
           (on-stop-edit)
           (reset! edition* false)
           (st/emit! (dw/end-rename-shape nil))))

        on-key-down
        (mf/use-fn
         (mf/deps accept-edit cancel-edit)
         (fn [event]
           (when (kbd/enter? event) (accept-edit))
           (when (kbd/esc? event) (cancel-edit))))

        parent-size (dm/str (- parent-size space-for-icons) "px")]

    (mf/with-effect [shape-for-rename edition? start-edit shape-id]
      (when (and (= shape-for-rename shape-id)
                 (not ^boolean edition?))
        (start-edit)))

    (mf/with-effect [edition?]
      (when edition?
        (some-> (mf/ref-val ref) dom/select-text!)
        nil))

    (if ^boolean edition?
      [:input
       {:class (if new-css-system
                 (dom/classnames (css :element-name-input) true)
                 (dom/classnames :element-name true))
        :style #js {"--depth" depth "--parent-size" parent-size}
        :type "text"
        :ref ref
        :on-blur accept-edit
        :on-key-down on-key-down
        :auto-focus true
        :default-value (or shape-name "")}]
      [:span
       {:class (if new-css-system
                 (dom/classnames (css :element-name) true
                                 (css :selected) selected?
                                 (css :hidden) hidden?
                                 (css :type-comp) type-comp
                                 (css :type-frame) type-frame)
                 (dom/classnames :element-name true))
        :style #js {"--depth" depth "--parent-size" parent-size}
        :ref ref
        :on-double-click start-edit}
       (or shape-name "")
       (when ^boolean shape-touched? " *")])))
