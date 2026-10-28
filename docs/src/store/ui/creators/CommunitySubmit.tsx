/**
 * "Submit to community" for the theme and layout builders: the web twin of the
 * app's Share with everyone dialog. Opens a pre-filled issue in the community
 * repository; nothing is sent from here.
 */
import { useState } from 'preact/hooks';
import { attachment, COMMUNITY_URL, LICENSES, plan, type Kind, type Plan } from '../../lib/community';
import { slugify } from '../../lib/util';
import { Notice } from '../common';
import { IconExternal, IconGlobe } from '../icons';
import { saveBytes, Select, Tags, Text } from './shared';

const LICENSE_NOTES: Record<string, string> = {
	'CC0-1.0': 'Anyone can use and change it, no credit needed.',
	'CC-BY-4.0': 'Anyone can use and change it if they credit you.',
	'CC-BY-SA-4.0': 'Anyone can use and change it if they credit you and share their changes the same way.',
};

export function CommunitySubmit({ kind, name, json, basedOn, disabled }: { kind: Kind; name: string; json: string; basedOn?: string | null; disabled?: boolean }) {
	const [description, setDescription] = useState('');
	const [tags, setTags] = useState<string[]>([]);
	const [license, setLicense] = useState<string>(LICENSES[0]);
	const [updates, setUpdates] = useState('');
	const [made, setMade] = useState<Plan | null>(null);

	const submit = async () => {
		const p = plan(kind, { name, description, tags, license, updates, basedOn: basedOn ?? '' }, json);
		setMade(p);
		if (p.tier === 'link') window.open(p.url, '_blank', 'noopener');
		else if (p.tier === 'paste') await navigator.clipboard?.writeText(p.encoded).catch(() => undefined);
		else saveBytes(`${slugify(name) || kind}.zip`, attachment(kind, json));
	};

	return (
		<details class="st-panel">
			<summary style="cursor:pointer"><b>Submit to community</b></summary>
			<p class="st-small st-muted" style="margin:0.5rem 0">
				Opens an issue in the <a href={COMMUNITY_URL} target="_blank" rel="noopener">community repository</a> with your {kind} filled in. A bot checks it and shows a preview; once approved, anyone can install it. Needs a GitHub account.
			</p>
			<div style="display:flex;flex-direction:column;gap:0.6rem">
				<Text label="Description" value={description} onInput={setDescription} />
				<Tags label="Tags" value={tags} onInput={setTags} placeholder="dark, pastel, minimal" />
				<Select label="Licence" value={license} onInput={setLicense} options={LICENSES} help={LICENSE_NOTES[license]} />
				<Text label="Replaces your earlier one (optional)" mono value={updates} onInput={setUpdates} help={`The id of a ${kind} you published before, to send a new version.`} />
				{basedOn && <span class="st-small st-muted">Credited as a remix of <code>{basedOn}</code>.</span>}
				<button class="st-btn st-btn-primary" disabled={disabled || !name.trim()} onClick={submit}><IconGlobe /> Open GitHub</button>
				{made?.tier === 'paste' && (
					<Notice kind="info">Too long for a link, so it was copied. <a href={made.url} target="_blank" rel="noopener">Open the issue <IconExternal /></a>, paste it into the Addon box, then press Submit.</Notice>
				)}
				{made?.tier === 'attach' && (
					<Notice kind="info">Too big to paste, so it was saved as a zip. <a href={made.url} target="_blank" rel="noopener">Open the issue <IconExternal /></a>, drag the file into the Addon box, then press Submit.</Notice>
				)}
			</div>
		</details>
	);
}
