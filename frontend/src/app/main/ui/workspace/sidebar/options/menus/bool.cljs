;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.bool
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.path.shapes-to-path :as dwps]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private flatten-icon
  (deprecated-icon/icon-xref :boolean-flatten (stl/css :flatten-icon)))

(mf/defc bool-options*
  [{:keys [total-selected shapes shapes-with-children]}]
  (let [head      (first shapes)
        head-id   (dm/get-prop head :id)

        is-group? (cfh/group-shape? head)
        is-bool?  (cfh/bool-shape? head)

        head-bool-type
        (and is-bool? (get head :bool-type))

        render-wasm-enabled?
        (features/use-feature "render-wasm/v1")

        has-invalid-shapes?
        (some (if render-wasm-enabled?
                cfh/frame-shape?
                #(or (cfh/frame-shape? %) (cfh/text-shape? %)))
              shapes-with-children)

        head-not-group-like?
        (and (= 1 total-selected)
             (not is-group?)
             (not is-bool?))

        disabled-bool-btns (or (zero? total-selected) has-invalid-shapes? head-not-group-like?)
        disabled-flatten   (or (zero? total-selected) has-invalid-shapes?)

        on-change
        (mf/use-fn
         (mf/deps total-selected is-group? is-bool? head-id head-bool-type)
         (fn [bool-type]
           (let [bool-type (keyword bool-type)]
             (cond
               (> total-selected 1)
               (st/emit! (dwb/create-bool bool-type))

               (and (= total-selected 1) is-group?)
               (st/emit! (dwb/group-to-bool head-id bool-type))

               (and (= total-selected 1) is-bool?)
               (if (= head-bool-type bool-type)
                 (st/emit! (dwb/bool-to-group head-id))
                 (st/emit! (dwb/change-bool-type head-id bool-type)))))))

        flatten-objects
        (mf/use-fn  #(st/emit! (dwps/convert-selected-to-path)))]

    (when (not (and disabled-bool-btns disabled-flatten))
      [:div {:class (stl/css :boolean-options)}
       [:div {:class (stl/css :bool-group)}
        [:& radio-buttons {:selected (d/name head-bool-type)
                           :class (stl/css :boolean-radio-btn)
                           :on-change on-change
                           :name "bool-options"}
         [:& radio-button {:icon deprecated-icon/boolean-union
                           :value "union"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.union") " (" (sc/get-tooltip :bool-union) ")")
                           :id "bool-opt-union"}]
         [:& radio-button {:icon deprecated-icon/boolean-difference
                           :value "difference"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.difference") " (" (sc/get-tooltip :bool-difference) ")")
                           :id "bool-opt-differente"}]
         [:& radio-button {:icon deprecated-icon/boolean-intersection
                           :value "intersection"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.intersection") " (" (sc/get-tooltip :bool-intersection) ")")
                           :id "bool-opt-intersection"}]
         [:& radio-button {:icon deprecated-icon/boolean-exclude
                           :value "exclude"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.exclude") " (" (sc/get-tooltip :bool-exclude) ")")
                           :id "bool-opt-exclude"}]]]

       [:button
        {:title (tr "workspace.shape.menu.flatten")
         :class (stl/css-case
                 :flatten-button true
                 :disabled disabled-flatten)
         :disabled disabled-flatten
         :on-click flatten-objects}
        flatten-icon]])))
