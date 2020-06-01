;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.migrations
  (:require
   [mount.core :as mount :refer [defstate]]
   [uxbox.db :as db]
   [uxbox.config :as cfg]
   [uxbox.util.migrations :as mg]))

(def +migrations+
  {:name "uxbox-main"
   :steps
   [{:desc "Add initial extensions and functions."
     :name "0001-add-extensions"
     :fn (mg/resource "migrations/0001-add-extensions.sql")}

    {:desc "Add profile related tables"
     :name "0002-add-profile-tables"
     :fn (mg/resource "migrations/0002-add-profile-tables.sql")}

    {:desc "Add project related tables"
     :name "0003-add-project-tables"
     :fn (mg/resource "migrations/0003-add-project-tables.sql")}

    {:desc "Add tasks related tables"
     :name "0004-add-tasks-tables"
     :fn (mg/resource "migrations/0004-add-tasks-tables.sql")}

    {:desc "Add libraries related tables"
     :name "0005-add-libraries-tables"
     :fn (mg/resource "migrations/0005-add-libraries-tables.sql")}

    {:desc "Add presence related tables"
     :name "0006-add-presence-tables"
     :fn (mg/resource "migrations/0006-add-presence-tables.sql")}

    {:desc "Drop version field from page table."
     :name "0007-drop-version-field-from-page-table"
     :fn (mg/resource "migrations/0007-drop-version-field-from-page-table.sql")}

    {:desc "Add generic token related tables."
     :name "0008-add-generic-token-table"
     :fn (mg/resource "migrations/0008-add-generic-token-table.sql")}

    {:desc "Drop the profile_email table"
     :name "0009-drop-profile-email-table"
     :fn (mg/resource "migrations/0009-drop-profile-email-table.sql")}

    {:desc "Add new HTTP session table"
     :name "0010-add-http-session-table"
     :fn (mg/resource "migrations/0010-add-http-session-table.sql")}]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn migrate
  []
  (with-open [conn (db/open)]
    (mg/setup! conn)
    (mg/migrate! conn +migrations+)))

(defstate migrations
  :start (migrate))
