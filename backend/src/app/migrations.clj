;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.migrations
  (:require
   [integrant.core :as ig]
   [app.db :as db]
   [app.util.migrations :as mg]
   [app.migrations.migration-0023 :as mg0023]))

(def migrations
  [{:name "0001-add-extensions"
    :fn (mg/resource "app/migrations/sql/0001-add-extensions.sql")}

   {:name "0002-add-profile-tables"
    :fn (mg/resource "app/migrations/sql/0002-add-profile-tables.sql")}

   {:name "0003-add-project-tables"
    :fn (mg/resource "app/migrations/sql/0003-add-project-tables.sql")}

   {:name "0004-add-tasks-tables"
    :fn (mg/resource "app/migrations/sql/0004-add-tasks-tables.sql")}

   {:name "0005-add-libraries-tables"
    :fn (mg/resource "app/migrations/sql/0005-add-libraries-tables.sql")}

   {:name "0006-add-presence-tables"
    :fn (mg/resource "app/migrations/sql/0006-add-presence-tables.sql")}

   {:name "0007-drop-version-field-from-page-table"
    :fn (mg/resource "app/migrations/sql/0007-drop-version-field-from-page-table.sql")}

   {:name "0008-add-generic-token-table"
    :fn (mg/resource "app/migrations/sql/0008-add-generic-token-table.sql")}

   {:name "0009-drop-profile-email-table"
    :fn (mg/resource "app/migrations/sql/0009-drop-profile-email-table.sql")}

   {:name "0010-add-http-session-table"
    :fn (mg/resource "app/migrations/sql/0010-add-http-session-table.sql")}

   {:name "0011-add-session-id-field-to-page-change-table"
    :fn (mg/resource "app/migrations/sql/0011-add-session-id-field-to-page-change-table.sql")}

   {:name "0012-make-libraries-linked-to-a-file"
    :fn (mg/resource "app/migrations/sql/0012-make-libraries-linked-to-a-file.sql")}

   {:name "0013-mark-files-shareable"
    :fn (mg/resource "app/migrations/sql/0013-mark-files-shareable.sql")}

   {:name "0014-refactor-media-storage.sql"
    :fn (mg/resource "app/migrations/sql/0014-refactor-media-storage.sql")}

   {:name "0015-improve-tasks-tables"
    :fn (mg/resource "app/migrations/sql/0015-improve-tasks-tables.sql")}

   {:name "0016-truncate-and-alter-tokens-table"
    :fn (mg/resource "app/migrations/sql/0016-truncate-and-alter-tokens-table.sql")}

   {:name "0017-link-files-to-libraries"
    :fn (mg/resource "app/migrations/sql/0017-link-files-to-libraries.sql")}

   {:name "0018-add-file-trimming-triggers"
    :fn (mg/resource "app/migrations/sql/0018-add-file-trimming-triggers.sql")}

   {:name "0019-add-improved-scheduled-tasks"
    :fn (mg/resource "app/migrations/sql/0019-add-improved-scheduled-tasks.sql")}

   {:name "0020-minor-fixes-to-media-object"
    :fn (mg/resource "app/migrations/sql/0020-minor-fixes-to-media-object.sql")}

   {:name "0021-http-session-improvements"
    :fn (mg/resource "app/migrations/sql/0021-http-session-improvements.sql")}

   {:name "0022-page-file-refactor"
    :fn (mg/resource "app/migrations/sql/0022-page-file-refactor.sql")}

   {:name "0023-adapt-old-pages-and-files"
    :fn mg0023/migrate}

   {:name "0024-mod-profile-table"
    :fn (mg/resource "app/migrations/sql/0024-mod-profile-table.sql")}

   {:name "0025-del-generic-tokens-table"
    :fn (mg/resource "app/migrations/sql/0025-del-generic-tokens-table.sql")}

   {:name "0026-mod-file-library-rel-table-synced-date"
    :fn (mg/resource "app/migrations/sql/0026-mod-file-library-rel-table-synced-date.sql")}

   {:name "0027-mod-file-table-ignore-sync"
    :fn (mg/resource "app/migrations/sql/0027-mod-file-table-ignore-sync.sql")}

   {:name "0028-add-team-project-profile-rel-table"
    :fn (mg/resource "app/migrations/sql/0028-add-team-project-profile-rel-table.sql")}

   {:name "0029-del-project-profile-rel-indexes"
    :fn (mg/resource "app/migrations/sql/0029-del-project-profile-rel-indexes.sql")}

   {:name "0030-mod-file-table-add-missing-index"
    :fn (mg/resource "app/migrations/sql/0030-mod-file-table-add-missing-index.sql")}

   {:name "0031-add-conversation-related-tables"
    :fn (mg/resource "app/migrations/sql/0031-add-conversation-related-tables.sql")}

   {:name "0032-del-unused-tables"
    :fn (mg/resource "app/migrations/sql/0032-del-unused-tables.sql")}

   {:name "0033-mod-comment-thread-table"
    :fn (mg/resource "app/migrations/sql/0033-mod-comment-thread-table.sql")}

   {:name "0034-mod-profile-table-add-props-field"
    :fn (mg/resource "app/migrations/sql/0034-mod-profile-table-add-props-field.sql")}

   {:name "0035-add-storage-tables"
    :fn (mg/resource "app/migrations/sql/0035-add-storage-tables.sql")}
   ])


(defmethod ig/init-key ::migrations [_ _] migrations)
