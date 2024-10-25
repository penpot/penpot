;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sets-context
  (:require
   [rumext.v2 :as mf]))

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
                 #(swap! ctx assoc :editing-id %))
        on-create (mf/use-fn
                   #(swap! ctx assoc :editing-id (random-uuid) :new? true))
        on-reset (mf/use-fn
                  #(reset! ctx initial))]
    {:editing? editing?
     :new? new?
     :on-edit on-edit
     :on-create on-create
     :on-reset on-reset}))
