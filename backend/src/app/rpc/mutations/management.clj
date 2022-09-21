;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.management
  "Move & Duplicate RPC methods for files and projects."
  (:require
   [app.db :as db]
   [app.rpc.commands.management :as cmd.mgm]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- MUTATION: Duplicate File

(s/def ::duplicate-file ::cmd.mgm/duplicate-file)

(sv/defmethod ::duplicate-file
  {::doc/added "1.2"
   ::doc/deprecated "1.16"}
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (cmd.mgm/duplicate-file conn params)))

;; --- MUTATION: Duplicate Project

(s/def ::duplicate-project ::cmd.mgm/duplicate-project)

(sv/defmethod ::duplicate-project
  {::doc/added "1.2"
   ::doc/deprecated "1.16"}
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (cmd.mgm/duplicate-project conn params)))

;; --- MUTATION: Move file

(s/def ::move-files ::cmd.mgm/move-files)

(sv/defmethod ::move-files
  {::doc/added "1.2"
   ::doc/deprecated "1.16"}
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (cmd.mgm/move-files conn params)))

;; --- MUTATION: Move project

(s/def ::move-project ::cmd.mgm/move-project)

(sv/defmethod ::move-project
  {::doc/added "1.2"
   ::doc/deprecated "1.16"}
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (cmd.mgm/move-project conn params)))
