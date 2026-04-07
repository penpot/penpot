// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { type ComponentPropsWithRef, memo } from "react";
import clsx from "clsx";
import { IconButton } from "../buttons/IconButton";
import styles from "./PanelTitle.module.scss";

export interface PanelTitleProps extends ComponentPropsWithRef<"div"> {
  /** Title text displayed in the panel header */
  text: string;
  /** When provided, renders a close (×) button that calls this handler */
  onClose?: () => void;
}

function PanelTitleInner({
  text,
  onClose,
  className,
  ...rest
}: PanelTitleProps) {
  return (
    <div className={clsx(styles["panel-title"], className)} {...rest}>
      <span className={styles["panel-title-text"]}>{text}</span>
      {onClose != null && (
        <IconButton
          variant="ghost"
          aria-label="Close"
          onClick={onClose}
          icon="close"
        />
      )}
    </div>
  );
}

export const PanelTitle = memo(PanelTitleInner);
