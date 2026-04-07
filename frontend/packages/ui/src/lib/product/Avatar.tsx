// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { type ElementType, memo, useMemo } from "react";
import clsx from "clsx";
import styles from "./Avatar.module.scss";

// ---------------------------------------------------------------------------
// Initials avatar generator (SVG-based, mirrors app.util.avatars/generate*)
// ---------------------------------------------------------------------------

const AVATAR_COLORS = [
  "#5b57ff",
  "#b1b1e5",
  "#e55b5b",
  "#e5a45b",
  "#5be587",
  "#5bd6e5",
];

function hashString(s: string): number {
  let hash = 0;
  for (let i = 0; i < s.length; i++) {
    hash = (hash << 5) - hash + s.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

function getInitials(name: string): string {
  const parts = name.trim().toUpperCase().split(/\s+/);
  if (parts.length === 0 || parts[0] === "") return "?";
  if (parts.length === 1) return parts[0][0] ?? "?";
  return (parts[0][0] ?? "") + (parts[1][0] ?? "");
}

function generateAvatarDataUri(name: string): string {
  const initials = getInitials(name);
  const color = AVATAR_COLORS[hashString(name) % AVATAR_COLORS.length];
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="${color}"/><text x="50%" y="67%" text-anchor="middle" font-family="Arial, sans-serif" font-size="28" fill="#fff">${initials}</text></svg>`;
  return `data:image/svg+xml;base64,${btoa(svg)}`;
}

// ---------------------------------------------------------------------------
// Profile type
// ---------------------------------------------------------------------------

export interface AvatarProfile {
  fullname: string;
  /** Pre-resolved photo URL. Takes precedence over photoId. */
  photoUrl?: string;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export type AvatarVariant = "S" | "M" | "L";

export interface AvatarProps {
  profile: AvatarProfile;
  /** Size variant. Defaults to "S". */
  variant?: AvatarVariant;
  /** Whether the avatar shows a selected ring. */
  selected?: boolean;
  /** Override the root element tag. Defaults to "div". */
  tag?: ElementType;
  /** Additional CSS class names */
  className?: string;
}

function AvatarInner({
  profile,
  variant = "S",
  selected = false,
  tag: Tag = "div",
  className,
}: AvatarProps) {
  const src = useMemo(() => {
    return profile.photoUrl ?? generateAvatarDataUri(profile.fullname);
  }, [profile.photoUrl, profile.fullname]);

  const rootClass = clsx(
    styles.avatar,
    {
      [styles["avatar-small"]]: variant === "S",
      [styles["avatar-medium"]]: variant === "M",
      [styles["avatar-large"]]: variant === "L",
      [styles["is-selected"]]: selected,
    },
    className,
  );

  return (
    <Tag className={rootClass} title={profile.fullname}>
      <div className={styles["avatar-image"]}>
        <img alt={profile.fullname} src={src} />
      </div>
    </Tag>
  );
}

export const Avatar = memo(AvatarInner);
