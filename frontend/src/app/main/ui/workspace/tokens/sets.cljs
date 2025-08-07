;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sets
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.workspace.tokens.sets.helpers :as sets-helpers]
   [app.main.ui.workspace.tokens.sets.lists :refer [controlled-sets-list*]]
   [rumext.v2 :as mf]))

(defn- on-select-token-set-click [name]
  (st/emit! (dwtl/set-selected-token-set-name name)))

(defn- on-toggle-token-set-click [name]
  (st/emit! (dwtl/toggle-token-set name)))

(defn- on-toggle-token-set-group-click [path]
  (st/emit! (dwtl/toggle-token-set-group path)))

(mf/defc sets-list*
  [{:keys [tokens-lib selected new-path edition-id]}]

  (let [token-sets
        (some-> tokens-lib (ctob/get-set-tree))

        can-edit?
        (mf/use-ctx ctx/can-edit?)

        active-token-sets-names
        (mf/with-memo [tokens-lib]
          (some-> tokens-lib (ctob/get-active-themes-set-names)))

        token-set-active?
        (mf/use-fn
         (mf/deps active-token-sets-names)
         (fn [name]
           (contains? active-token-sets-names name)))

        token-set-group-active?
        (mf/use-fn
         (fn [group-path]
           ;; FIXME
           @(refs/token-sets-at-path-all-active group-path)))

        on-reset-edition
        (mf/use-fn
         (mf/deps can-edit?)
         (fn [_]
           (when can-edit?
             (st/emit! (dwtl/clear-token-set-edition)
                       (dwtl/clear-token-set-creation)))))

        on-start-edition
        (mf/use-fn
         (mf/deps can-edit?)
         (fn [id]
           (when can-edit?
             (st/emit! (dwtl/start-token-set-edition id)))))]

    [:> controlled-sets-list*
     {:token-sets token-sets

      :is-token-set-active token-set-active?
      :is-token-set-group-active token-set-group-active?
      :on-select on-select-token-set-click

      :selected selected
      :new-path new-path
      :edition-id edition-id

      :origin "set-panel"
      :can-edit can-edit?
      :on-start-edition on-start-edition
      :on-reset-edition on-reset-edition

      :on-toggle-token-set on-toggle-token-set-click
      :on-toggle-token-set-group on-toggle-token-set-group-click
      :on-update-token-set sets-helpers/on-update-token-set
      :on-update-token-set-group sets-helpers/on-update-token-set-group
      :on-create-token-set sets-helpers/on-create-token-set}]))
