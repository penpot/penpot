;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.migrations
  (:require
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.db :as db]
   [app.migrations.clj.migration-0023 :as mg0023]
   [app.util.migrations :as mg]
   [integrant.core :as ig]))

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

   {:name "0036-mod-storage-referenced-tables"
    :fn (mg/resource "app/migrations/sql/0036-mod-storage-referenced-tables.sql")}

   {:name "0037-del-obsolete-triggers"
    :fn (mg/resource "app/migrations/sql/0037-del-obsolete-triggers.sql")}

   {:name "0038-add-storage-on-delete-triggers"
    :fn (mg/resource "app/migrations/sql/0038-add-storage-on-delete-triggers.sql")}

   {:name "0039-fix-some-on-delete-triggers"
    :fn (mg/resource "app/migrations/sql/0039-fix-some-on-delete-triggers.sql")}

   {:name "0040-add-error-report-tables"
    :fn (mg/resource "app/migrations/sql/0040-add-error-report-tables.sql")}

   {:name "0041-mod-pg-storage-options"
    :fn (mg/resource "app/migrations/sql/0041-mod-pg-storage-options.sql")}

   {:name "0042-add-server-prop-table"
    :fn (mg/resource "app/migrations/sql/0042-add-server-prop-table.sql")}

   {:name "0043-drop-old-tables-and-fields"
    :fn (mg/resource "app/migrations/sql/0043-drop-old-tables-and-fields.sql")}

   {:name "0044-add-storage-refcount"
    :fn (mg/resource "app/migrations/sql/0044-add-storage-refcount.sql")}

   {:name "0045-add-index-to-file-change-table"
    :fn (mg/resource "app/migrations/sql/0045-add-index-to-file-change-table.sql")}

   {:name "0046-add-profile-complaint-table"
    :fn (mg/resource "app/migrations/sql/0046-add-profile-complaint-table.sql")}

   {:name "0047-mod-file-change-table"
    :fn (mg/resource "app/migrations/sql/0047-mod-file-change-table.sql")}

   {:name "0048-mod-storage-tables"
    :fn (mg/resource "app/migrations/sql/0048-mod-storage-tables.sql")}

   {:name "0049-mod-http-session-table"
    :fn (mg/resource "app/migrations/sql/0049-mod-http-session-table.sql")}

   {:name "0050-mod-server-prop-table"
    :fn (mg/resource "app/migrations/sql/0050-mod-server-prop-table.sql")}

   {:name "0051-mod-file-library-rel-table"
    :fn (mg/resource "app/migrations/sql/0051-mod-file-library-rel-table.sql")}

   {:name "0052-del-legacy-user-and-team"
    :fn (mg/resource "app/migrations/sql/0052-del-legacy-user-and-team.sql")}

   {:name "0053-add-team-font-variant-table"
    :fn (mg/resource "app/migrations/sql/0053-add-team-font-variant-table.sql")}

   {:name "0054-add-audit-log-table"
    :fn (mg/resource "app/migrations/sql/0054-add-audit-log-table.sql")}

   {:name "0055-mod-file-media-object-table"
    :fn (mg/resource "app/migrations/sql/0055-mod-file-media-object-table.sql")}

   {:name "0056-add-missing-index-on-deleted-at"
    :fn (mg/resource "app/migrations/sql/0056-add-missing-index-on-deleted-at.sql")}

   {:name "0057-del-profile-on-delete-trigger"
    :fn (mg/resource "app/migrations/sql/0057-del-profile-on-delete-trigger.sql")}

   {:name "0058-del-team-on-delete-trigger"
    :fn (mg/resource "app/migrations/sql/0058-del-team-on-delete-trigger.sql")}

   {:name "0059-mod-audit-log-table"
    :fn (mg/resource "app/migrations/sql/0059-mod-audit-log-table.sql")}

   {:name "0060-mod-file-change-table"
    :fn (mg/resource "app/migrations/sql/0060-mod-file-change-table.sql")}

   {:name "0061-mod-file-table"
    :fn (mg/resource "app/migrations/sql/0061-mod-file-table.sql")}

   {:name "0062-fix-metadata-media"
    :fn (mg/resource "app/migrations/sql/0062-fix-metadata-media.sql")}

   {:name "0063-add-share-link-table"
    :fn (mg/resource "app/migrations/sql/0063-add-share-link-table.sql")}

   {:name "0064-mod-audit-log-table"
    :fn (mg/resource "app/migrations/sql/0064-mod-audit-log-table.sql")}

   {:name "0065-add-trivial-spelling-fixes"
    :fn (mg/resource "app/migrations/sql/0065-add-trivial-spelling-fixes.sql")}

   {:name "0066-add-frame-thumbnail-table"
    :fn (mg/resource "app/migrations/sql/0066-add-frame-thumbnail-table.sql")}

   {:name "0067-add-team-invitation-table"
    :fn (mg/resource "app/migrations/sql/0067-add-team-invitation-table.sql")}

   {:name "0068-mod-storage-object-table"
    :fn (mg/resource "app/migrations/sql/0068-mod-storage-object-table.sql")}

   {:name "0069-add-file-thumbnail-table"
    :fn (mg/resource "app/migrations/sql/0069-add-file-thumbnail-table.sql")}

   {:name "0070-del-frame-thumbnail-table"
    :fn (mg/resource "app/migrations/sql/0070-del-frame-thumbnail-table.sql")}

   {:name "0071-add-file-object-thumbnail-table"
    :fn (mg/resource "app/migrations/sql/0071-add-file-object-thumbnail-table.sql")}

   {:name "0072-mod-file-object-thumbnail-table"
    :fn (mg/resource "app/migrations/sql/0072-mod-file-object-thumbnail-table.sql")}

   {:name "0073-mod-file-media-object-constraints"
    :fn (mg/resource "app/migrations/sql/0073-mod-file-media-object-constraints.sql")}

   {:name "0074-mod-file-library-rel-constraints"
    :fn (mg/resource "app/migrations/sql/0074-mod-file-library-rel-constraints.sql")}

   {:name "0075-mod-share-link-table"
    :fn (mg/resource "app/migrations/sql/0075-mod-share-link-table.sql")}

   {:name "0076-mod-storage-object-table"
    :fn (mg/resource "app/migrations/sql/0076-mod-storage-object-table.sql")}

   {:name "0077-mod-comment-thread-table"
    :fn (mg/resource "app/migrations/sql/0077-mod-comment-thread-table.sql")}

   {:name "0078-mod-file-media-object-table-drop-cascade"
    :fn (mg/resource "app/migrations/sql/0078-mod-file-media-object-table-drop-cascade.sql")}

   {:name "0079-mod-profile-table"
    :fn (mg/resource "app/migrations/sql/0079-mod-profile-table.sql")}

   {:name "0080-mod-index-names"
    :fn (mg/resource "app/migrations/sql/0080-mod-index-names.sql")}

   {:name "0081-add-deleted-at-index-to-file-table"
    :fn (mg/resource "app/migrations/sql/0081-add-deleted-at-index-to-file-table.sql")}

   {:name "0082-add-features-column-to-file-table"
    :fn (mg/resource "app/migrations/sql/0082-add-features-column-to-file-table.sql")}

   {:name "0083-add-file-data-fragment-table"
    :fn (mg/resource "app/migrations/sql/0083-add-file-data-fragment-table.sql")}

   {:name "0084-add-features-column-to-file-change-table"
    :fn (mg/resource "app/migrations/sql/0084-add-features-column-to-file-change-table.sql")}

   {:name "0085-add-webhook-table"
    :fn (mg/resource "app/migrations/sql/0085-add-webhook-table.sql")}

   {:name "0086-add-webhook-delivery-table"
    :fn (mg/resource "app/migrations/sql/0086-add-webhook-delivery-table.sql")}

   {:name "0087-mod-task-table"
    :fn (mg/resource "app/migrations/sql/0087-mod-task-table.sql")}

   {:name "0088-mod-team-profile-rel-table"
    :fn (mg/resource "app/migrations/sql/0088-mod-team-profile-rel-table.sql")}

   {:name "0089-mod-project-profile-rel-table"
    :fn (mg/resource "app/migrations/sql/0089-mod-project-profile-rel-table.sql")}

   {:name "0090-mod-http-session-table"
    :fn (mg/resource "app/migrations/sql/0090-mod-http-session-table.sql")}

   {:name "0091-mod-team-project-profile-rel-table"
    :fn (mg/resource "app/migrations/sql/0091-mod-team-project-profile-rel-table.sql")}

   {:name "0092-mod-team-invitation-table"
    :fn (mg/resource "app/migrations/sql/0092-mod-team-invitation-table.sql")}

   {:name "0093-del-file-share-tokens-table"
    :fn (mg/resource "app/migrations/sql/0093-del-file-share-tokens-table.sql")}

   {:name "0094-del-profile-attr-table"
    :fn (mg/resource "app/migrations/sql/0094-del-profile-attr-table.sql")}

   {:name "0095-del-storage-data-table"
    :fn (mg/resource "app/migrations/sql/0095-del-storage-data-table.sql")}

   {:name "0096-del-storage-pending-table"
    :fn (mg/resource "app/migrations/sql/0096-del-storage-pending-table.sql")}

   {:name "0098-add-quotes-table"
    :fn (mg/resource "app/migrations/sql/0098-add-quotes-table.sql")}

   {:name "0099-add-access-token-table"
    :fn (mg/resource "app/migrations/sql/0099-add-access-token-table.sql")}

   {:name "0100-mod-profile-indexes"
    :fn (mg/resource "app/migrations/sql/0100-mod-profile-indexes.sql")}

   {:name "0101-mod-server-error-report-table"
    :fn (mg/resource "app/migrations/sql/0101-mod-server-error-report-table.sql")}

   {:name "0102-mod-access-token-table"
    :fn (mg/resource "app/migrations/sql/0102-mod-access-token-table.sql")}

   {:name "0103-mod-file-object-thumbnail-table"
    :fn (mg/resource "app/migrations/sql/0103-mod-file-object-thumbnail-table.sql")}

   {:name "0104-mod-file-thumbnail-table"
    :fn (mg/resource "app/migrations/sql/0104-mod-file-thumbnail-table.sql")}

   {:name "0105-mod-file-change-table"
    :fn (mg/resource "app/migrations/sql/0105-mod-file-change-table.sql")}

   {:name "0105-mod-server-error-report-table"
    :fn (mg/resource "app/migrations/sql/0105-mod-server-error-report-table.sql")}

   {:name "0106-add-file-tagged-object-thumbnail-table"
    :fn (mg/resource "app/migrations/sql/0106-add-file-tagged-object-thumbnail-table.sql")}

   {:name "0106-mod-team-table"
    :fn (mg/resource "app/migrations/sql/0106-mod-team-table.sql")}

   {:name "0107-mod-file-tagged-object-thumbnail-table"
    :fn (mg/resource "app/migrations/sql/0107-mod-file-tagged-object-thumbnail-table.sql")}

   {:name "0107-add-deletion-protection-trigger-function"
    :fn (mg/resource "app/migrations/sql/0107-add-deletion-protection-trigger-function.sql")}

   {:name "0108-mod-file-thumbnail-table"
    :fn (mg/resource "app/migrations/sql/0108-mod-file-thumbnail-table.sql")}

   {:name "0109-mod-file-tagged-object-thumbnail-table"
    :fn (mg/resource "app/migrations/sql/0109-mod-file-tagged-object-thumbnail-table.sql")}

   {:name "0110-mod-file-media-object-table"
    :fn (mg/resource "app/migrations/sql/0110-mod-file-media-object-table.sql")}

   {:name "0111-mod-file-data-fragment-table"
    :fn (mg/resource "app/migrations/sql/0111-mod-file-data-fragment-table.sql")}

   {:name "0112-mod-profile-table"
    :fn (mg/resource "app/migrations/sql/0112-mod-profile-table.sql")}

   {:name "0113-mod-team-font-variant-table"
    :fn (mg/resource "app/migrations/sql/0113-mod-team-font-variant-table.sql")}

   {:name "0114-mod-team-table"
    :fn (mg/resource "app/migrations/sql/0114-mod-team-table.sql")}

   {:name "0115-mod-project-table"
    :fn (mg/resource "app/migrations/sql/0115-mod-project-table.sql")}

   {:name "0116-mod-file-table"
    :fn (mg/resource "app/migrations/sql/0116-mod-file-table.sql")}

   {:name "0117-mod-file-object-thumbnail-table"
    :fn (mg/resource "app/migrations/sql/0117-mod-file-object-thumbnail-table.sql")}

   {:name "0118-mod-task-table"
    :fn (mg/resource "app/migrations/sql/0118-mod-task-table.sql")}

   {:name "0119-mod-file-table"
    :fn (mg/resource "app/migrations/sql/0119-mod-file-table.sql")}

   {:name "0120-mod-audit-log-table"
    :fn (mg/resource "app/migrations/sql/0120-mod-audit-log-table.sql")}

   {:name "0121-mod-file-data-fragment-table"
    :fn (mg/resource "app/migrations/sql/0121-mod-file-data-fragment-table.sql")}

   {:name "0122-mod-file-table"
    :fn (mg/resource "app/migrations/sql/0122-mod-file-table.sql")}

   {:name "0122-mod-file-data-fragment-table"
    :fn (mg/resource "app/migrations/sql/0122-mod-file-data-fragment-table.sql")}

   {:name "0123-mod-file-change-table"
    :fn (mg/resource "app/migrations/sql/0123-mod-file-change-table.sql")}

   {:name "0124-mod-profile-table"
    :fn (mg/resource "app/migrations/sql/0124-mod-profile-table.sql")}

   {:name "0125-mod-file-table"
    :fn (mg/resource "app/migrations/sql/0125-mod-file-table.sql")}

   {:name "0126-add-team-access-request-table"
    :fn (mg/resource "app/migrations/sql/0126-add-team-access-request-table.sql")}

   {:name "0127-mod-storage-object-table"
    :fn (mg/resource "app/migrations/sql/0127-mod-storage-object-table.sql")}

   {:name "0128-mod-task-table"
    :fn (mg/resource "app/migrations/sql/0128-mod-task-table.sql")}

   {:name "0129-mod-file-change-table"
    :fn (mg/resource "app/migrations/sql/0129-mod-file-change-table.sql")}

   {:name "0130-mod-file-change-table"
    :fn (mg/resource "app/migrations/sql/0130-mod-file-change-table.sql")}

   {:name "0131-mod-webhook-table"
    :fn (mg/resource "app/migrations/sql/0131-mod-webhook-table.sql")}

   {:name "0132-mod-file-change-table"
    :fn (mg/resource "app/migrations/sql/0132-mod-file-change-table.sql")}

   {:name "0133-mod-file-table"
    :fn (mg/resource "app/migrations/sql/0133-mod-file-table.sql")}

   {:name "0134-mod-file-change-table"
    :fn (mg/resource "app/migrations/sql/0134-mod-file-change-table.sql")}

   {:name "0135-mod-team-invitation-table.sql"
    :fn (mg/resource "app/migrations/sql/0135-mod-team-invitation-table.sql")}

   {:name "0136-mod-comments-mentions.sql"
    :fn (mg/resource "app/migrations/sql/0136-mod-comments-mentions.sql")}

   {:name "0137-add-file-migration-table.sql"
    :fn (mg/resource "app/migrations/sql/0137-add-file-migration-table.sql")}

   {:name "0138-mod-file-data-fragment-table.sql"
    :fn (mg/resource "app/migrations/sql/0138-mod-file-data-fragment-table.sql")}

   {:name "0139-mod-file-change-table.sql"
    :fn (mg/resource "app/migrations/sql/0139-mod-file-change-table.sql")}

   {:name "0140-mod-file-change-table.sql"
    :fn (mg/resource "app/migrations/sql/0140-mod-file-change-table.sql")}

   {:name "0140-add-locked-by-column-to-file-change-table"
    :fn (mg/resource "app/migrations/sql/0140-add-locked-by-column-to-file-change-table.sql")}

   {:name "0141-add-idx-to-file-library-rel"
    :fn (mg/resource "app/migrations/sql/0141-add-idx-to-file-library-rel.sql")}

   {:name "0141-add-file-data-table.sql"
    :fn (mg/resource "app/migrations/sql/0141-add-file-data-table.sql")}

   {:name "0142-add-sso-provider-table"
    :fn (mg/resource "app/migrations/sql/0142-add-sso-provider-table.sql")}

   {:name "0143-http-session-v2-table"
    :fn (mg/resource "app/migrations/sql/0143-add-http-session-v2-table.sql")}])

(defn apply-migrations!
  [pool name migrations]
  (dm/with-open [conn (db/open pool)]
    (mg/setup! conn)
    (mg/migrate! conn {:name name :steps migrations})))

(defmethod ig/assert-key ::migrations
  [_ {:keys [::db/pool]}]
  (assert (db/pool? pool) "expected valid pool"))

(defmethod ig/init-key ::migrations
  [module {:keys [::db/pool]}]
  (when-not (db/read-only? pool)
    (l/info :hint "running migrations" :module module)
    (some->> (seq migrations) (apply-migrations! pool "main"))))
