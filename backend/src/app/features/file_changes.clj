;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.features.file-changes
  "The kinds of the rows of the `file_change` table.

  A snapshot is the state of a file at a point: it has a label and its
  data lives in `file_data`. A revision is the delta of a save: it has
  no label and it holds the changes.")

(def snapshot-kind
  "The kind of a snapshot row."
  1)

(def revision-kind
  "The kind of a revision row."
  2)
