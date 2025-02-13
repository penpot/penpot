;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.layer-name
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.util.debug :as dbg]
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
  [{:keys [shape-id shape-name is-shape-touched disabled-double-click
           on-start-edit on-stop-edit depth parent-size is-selected
           type-comp type-frame is-hidden is-blocked]} external-ref]
  (let [edition*         (mf/use-state false)
        edition?         (deref edition*)

        local-ref        (mf/use-ref)
        ref              (d/nilv external-ref local-ref)

        shape-for-rename (mf/deref lens:shape-for-rename)

        has-path?        (str/includes? shape-name "/")

        start-edit
        (mf/use-fn
         (mf/deps disabled-double-click on-start-edit shape-id is-blocked)
         (fn []
           (when (and (not is-blocked)
                      (not disabled-double-click))
             (on-start-edit)
             (reset! edition* true)
             (st/emit! (dw/start-rename-shape shape-id)))))

        accept-edit
        (mf/use-fn
         (mf/deps shape-id on-stop-edit)
         (fn []
           (let [name-input     (mf/ref-val ref)
                 name           (str/trim (dom/get-value name-input))]
             (on-stop-edit)
             (reset! edition* false)
             (st/emit! (dw/end-rename-shape shape-id name)))))

        cancel-edit
        (mf/use-fn
         (mf/deps shape-id on-stop-edit)
         (fn []
           (on-stop-edit)
           (reset! edition* false)
           (st/emit! (dw/end-rename-shape shape-id nil))))

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
       {:class (stl/css :element-name
                        :element-name-input)
        :style {"--depth" depth "--parent-size" parent-size}
        :type "text"
        :ref ref
        :on-blur accept-edit
        :on-key-down on-key-down
        :auto-focus true
        :default-value (d/nilv shape-name "")}]
      [:*
       [:span
        {:class (stl/css-case
                 :element-name true
                 :left-ellipsis has-path?
                 :selected is-selected
                 :hidden is-hidden
                 :type-comp type-comp
                 :type-frame type-frame)
         :style {"--depth" depth "--parent-size" parent-size}
         :ref ref
         :on-double-click start-edit}
        (if (dbg/enabled? :show-ids)
          (str (d/nilv shape-name "") " | " (str/slice (str shape-id) 24))
          (d/nilv shape-name ""))]
       (when (and (dbg/enabled? :show-touched) ^boolean is-shape-touched)
         [:span {:class (stl/css :element-name-touched)} "*"])])))
