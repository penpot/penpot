;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.i18n
  "Dummy i18n functions, to be used by code in common that needs translations.")

(defn tr
  "This function will be monkeypatched at runtime with the real function in frontend i18n.
   Here it just returns the key passed as argument. This way the result can be used in
   unit tests or backend code for logs or error messages."
  [key & _args]
  key)
