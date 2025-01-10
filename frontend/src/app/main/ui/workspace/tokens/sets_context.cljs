;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sets-context
  (:require
   [app.common.data.macros :as dm]
   [rumext.v2 :as mf]))

(defn set-group-path->id [set-group-path]
  (dm/str "group-" set-group-path))

(defn set-path->id [set-path]
  (dm/str "set-" set-path))

(def initial {:editing-id nil
              :new? false})

(def context (mf/create-context initial))

(def static-context
  {:editing? (constantly false)
   :new? false
   :on-edit (constantly nil)
   :on-create (constantly nil)
   :on-reset (constantly nil)})

(mf/defc provider
  {::mf/wrap-props false}
  [props]
  (let [children (unchecked-get props "children")
        state (mf/use-state initial)]
    [:& (mf/provider context) {:value state}
     children]))

(defn use-context []
  (let [ctx (mf/use-ctx context)
        {:keys [editing-id new?]} @ctx
        editing? (mf/use-callback
                  (mf/deps editing-id)
                  #(= editing-id %))
        on-edit (mf/use-fn
                 (fn [editing-id]
                   (reset! ctx (assoc @ctx :editing-id editing-id))))
        on-create (mf/use-fn
                   #(swap! ctx assoc :editing-id (random-uuid) :new? true))
        on-reset (mf/use-fn
                  #(reset! ctx initial))]
    {:editing? editing?
     :new? new?
     :on-edit on-edit
     :on-create on-create
     :on-reset on-reset}))
