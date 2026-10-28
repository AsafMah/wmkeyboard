/**
 * Sending a theme or layout to the community repository: a port of the app's
 * CommunitySubmission.kt. Change one, change the other; both are held to
 * app/src/test/resources/addons/community-submission-vectors.json.
 *
 * The payload is `wmkb:1:` + base64url (no padding) of the zlib-wrapped
 * DEFLATE of the UTF-8 JSON. It reaches the issue in the link, pasted, or as
 * an attached zip, whichever is the smallest that fits.
 */
import { strFromU8, strToU8, unzlibSync, zipSync, zlibSync } from 'fflate';

export const COMMUNITY_URL = 'https://github.com/wasi-master/wmkeyboard-community-addons';
export const PREFIX = 'wmkb:1:';
/** Longest link opened with the payload in it; GitHub answers a longer one with an error page. */
export const LINK_LIMIT = 7_500;
/** Longest payload worth pasting: an issue body holds 65,536 characters. */
export const PASTE_LIMIT = 60_000;
export const LICENSES = ['CC0-1.0', 'CC-BY-4.0', 'CC-BY-SA-4.0'] as const;

export type Kind = 'theme' | 'layout';
export type Tier = 'link' | 'paste' | 'attach';

export const KINDS: Record<Kind, { template: string; fileName: string; title: string }> = {
	theme: { template: 'submit-theme.yml', fileName: 'theme.wmtheme.json', title: '[Theme]' },
	layout: { template: 'submit-layout.yml', fileName: 'layout.wmlayout.json', title: '[Layout]' },
};

export interface Fields {
	name: string;
	description?: string;
	tags?: string[];
	license?: string;
	/** Id of an addon this user published before. */
	updates?: string;
	/** `"<repoId>/<addonId>"` of the addon this one remixes. */
	basedOn?: string;
}

export interface Plan {
	tier: Tier;
	/** Carries the payload only on 'link'. */
	url: string;
	encoded: string;
}

function toBase64Url(bytes: Uint8Array): string {
	let s = '';
	for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
	return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(text: string): Uint8Array | null {
	const clean = text.replace(/\s+/g, '').replace(/=+$/, '').replace(/-/g, '+').replace(/_/g, '/');
	if (!/^[A-Za-z0-9+/]*$/.test(clean) || clean.length % 4 === 1) return null;
	try {
		const s = atob(clean + '='.repeat((4 - (clean.length % 4)) % 4));
		const out = new Uint8Array(s.length);
		for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
		return out;
	} catch {
		return null;
	}
}

export function encodePayload(json: string): string {
	return PREFIX + toBase64Url(zlibSync(strToU8(json), { level: 9 }));
}

/** The JSON in `text`, or null when it is not an encoded payload. */
export function decodePayload(text: string): string | null {
	const t = text.trim();
	if (!t.startsWith(PREFIX)) return null;
	const bytes = fromBase64Url(t.slice(PREFIX.length));
	if (!bytes) return null;
	try {
		return strFromU8(unzlibSync(bytes));
	} catch {
		return null;
	}
}

/** The link that opens `kind`'s issue form filled in; `%20` for spaces, which GitHub's prefill needs. */
export function issueUrl(kind: Kind, f: Fields, payload: string | null): string {
	const params: [string, string][] = [
		['template', KINDS[kind].template],
		['title', `${KINDS[kind].title} ${f.name.trim()}`],
		['name', f.name.trim()],
	];
	if (f.description?.trim()) params.push(['description', f.description.trim()]);
	const tags = [...new Set((f.tags ?? []).map((t) => t.trim()).filter(Boolean))];
	if (tags.length) params.push(['tags', tags.join(', ')]);
	params.push(['license', f.license ?? LICENSES[0]]);
	if (f.updates?.trim()) params.push(['updates', f.updates.trim()]);
	if (f.basedOn?.trim()) params.push(['based_on', f.basedOn.trim()]);
	if (payload != null) params.push(['payload', payload]);
	return `${COMMUNITY_URL}/issues/new?` + params.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
}

export function plan(kind: Kind, f: Fields, json: string): Plan {
	const encoded = encodePayload(json);
	const withPayload = issueUrl(kind, f, encoded);
	if (withPayload.length <= LINK_LIMIT) return { tier: 'link', url: withPayload, encoded };
	return { tier: encoded.length <= PASTE_LIMIT ? 'paste' : 'attach', url: issueUrl(kind, f, null), encoded };
}

/** The zip an 'attach' submission saves: GitHub takes a .zip in an issue, not a .json. */
export function attachment(kind: Kind, json: string): Uint8Array {
	return zipSync({ [KINDS[kind].fileName]: strToU8(json) });
}
