;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.path.common
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.path.state :as pst]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def accent-color "var(--color-accent-tertiary)")
(def secondary-color "var(--color-accent-quaternary)")
(def black-color "var(--app-black)")
(def white-color "var(--app-white)")
(def gray-color "var(--df-secondary)")

(def current-edit-path-ref
  (l/derived
   (fn [state]
     (let [id (pst/get-path-id state)]
       (dm/get-in state [:workspace-local :edit-path id])))
   st/state))

(defn make-edit-path-ref [id]
  (mf/use-memo
   (mf/deps id)
   (let [selfn #(get-in % [:edit-path id])]
     #(l/derived selfn refs/workspace-local))))

(defn content-modifiers-ref
  [id]
  (l/derived #(get-in % [:edit-path id :content-modifiers]) refs/workspace-local))

(defn make-content-modifiers-ref [id]
  (mf/use-memo
   (mf/deps id)
   #(content-modifiers-ref id)))

