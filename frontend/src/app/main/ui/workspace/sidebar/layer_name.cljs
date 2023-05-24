;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.layer-name
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def shape-for-rename-ref
  (l/derived (l/in [:workspace-local :shape-for-rename]) st/state))

(mf/defc layer-name
  [{:keys [shape on-start-edit  disabled-double-click on-stop-edit name-ref depth parent-size selected? type-comp type-frame hidden] :as props}]
  (let [local            (mf/use-state {})
        shape-for-rename (mf/deref shape-for-rename-ref)
        new-css-system   (mf/use-ctx ctx/new-css-system)

        start-edit (fn []
                     (when (not disabled-double-click)
                       (on-start-edit)
                       (swap! local assoc :edition true)
                       (st/emit! (dw/start-rename-shape (:id shape)))))

        accept-edit (fn []
                      (let [name-input     (mf/ref-val name-ref)
                            name           (str/trim (dom/get-value name-input))]
                        (on-stop-edit)
                        (swap! local assoc :edition false)
                        (st/emit! (dw/end-rename-shape name))))

        cancel-edit (fn []
                      (on-stop-edit)
                      (swap! local assoc :edition false)
                      (st/emit! (dw/end-rename-shape nil)))

        on-key-down (fn [event]
                      (when (kbd/enter? event) (accept-edit))
                      (when (kbd/esc? event) (cancel-edit)))

        space-for-icons 110
        parent-size (str (- parent-size space-for-icons) "px")]

    (mf/with-effect [shape-for-rename]
      (when (and (= shape-for-rename (:id shape))
                 (not (:edition @local)))
        (start-edit)))

    (mf/with-effect [(:edition @local)]
      (when (:edition @local)
        (let [name-input (mf/ref-val name-ref)]
          (dom/select-text! name-input)
          nil)))

    (if (:edition @local)
      [:input
       {:class (if new-css-system
                 (dom/classnames (css :element-name-input) true)
                 (dom/classnames :element-name true))
        :style #js {"--depth" depth "--parent-size" parent-size}
        :type "text"
        :ref name-ref
        :on-blur accept-edit
        :on-key-down on-key-down
        :auto-focus true
        :default-value (:name shape "")}]
      [:span
       {:class (if new-css-system
                 (dom/classnames (css :element-name) true
                                 (css :selected) selected?
                                 (css :hidden) hidden
                                 (css :type-comp) type-comp
                                 (css :type-frame) type-frame)
                 (dom/classnames :element-name true))
        :style #js {"--depth" depth "--parent-size" parent-size}
        :ref name-ref
        :on-double-click start-edit}
       (:name shape "")
       (when (seq (:touched shape)) " *")])))
