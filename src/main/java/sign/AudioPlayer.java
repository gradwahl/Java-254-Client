package sign;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.sound.midi.MetaEventListener;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;

final class AudioPlayer {

	private static final List<Clip> clips = new ArrayList<>();

	private static Sequencer sequencer;

	private static Synthesizer synthesizer;

	private static VolumeReceiver midiReceiver;

	private static boolean midiPaused;

	private AudioPlayer() {
	}

	static synchronized void playWave(String path, int volume) {
		removeClosedClips();
		try {
			AudioInputStream stream = AudioSystem.getAudioInputStream(new File(path));
			Clip clip = AudioSystem.getClip();
			clip.addLineListener(event -> {
				if (event.getType() == LineEvent.Type.STOP) {
					clip.close();
				}
			});
			clip.open(stream);
			stream.close();
			setWaveVolume(clip, volume);
			clips.add(clip);
			clip.start();
		} catch (Exception ex) {
			System.err.println("Unable to play wave audio: " + ex.getMessage());
		}
	}

	static synchronized void setWaveVolume(int volume) {
		removeClosedClips();
		for (Clip clip : clips) {
			setWaveVolume(clip, volume);
		}
	}

	static synchronized void playMidi(String path, int volume) {
		try {
			stopMidi();
			synthesizer = MidiSystem.getSynthesizer();
			synthesizer.open();
			sequencer = MidiSystem.getSequencer(false);
			sequencer.open();
			midiReceiver = new VolumeReceiver(synthesizer.getReceiver(), volume);
			sequencer.getTransmitter().setReceiver(midiReceiver);
			sequencer.setSequence(MidiSystem.getSequence(new File(path)));
			sequencer.setTempoFactor(1.0F);
			midiPaused = false;
			sequencer.addMetaEventListener(createLoopVolumeListener());
			sequencer.start();
		} catch (Exception ex) {
			System.err.println("Unable to play midi audio: " + ex.getMessage());
			stopMidi();
		}
	}

	static synchronized void stopMidi() {
		midiPaused = false;
		if (sequencer != null) {
			sequencer.stop();
			sequencer.close();
			sequencer = null;
		}
		if (synthesizer != null) {
			synthesizer.close();
			synthesizer = null;
		}
		midiReceiver = null;
	}

	static synchronized void pauseMidi() {
		if (sequencer != null && sequencer.isRunning()) {
			sequencer.stop();
			midiPaused = true;
		}
	}

	static synchronized boolean resumeMidi() {
		if (!midiPaused || sequencer == null || !sequencer.isOpen()) {
			return false;
		}
		midiPaused = false;
		sequencer.start();
		return true;
	}

	static synchronized void setMidiVolume(int volume) {
		if (midiReceiver != null) {
			midiReceiver.setVolume(volume);
		}
	}

	private static MetaEventListener createLoopVolumeListener() {
		return meta -> {
			if (meta.getType() == 47) { // end of track
				synchronized (AudioPlayer.class) {
					if (midiReceiver != null) {
						midiReceiver.reapplyVolume();
					}
				}
			}
		};
	}

	private static void setWaveVolume(Clip clip, int volume) {
		if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
			return;
		}
		FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
		float decibels = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), volume / 100.0F));
		gain.setValue(decibels);
	}

	private static void removeClosedClips() {
		Iterator<Clip> iterator = clips.iterator();
		while (iterator.hasNext()) {
			if (!iterator.next().isOpen()) {
				iterator.remove();
			}
		}
	}

	private static final class VolumeReceiver implements Receiver {

		private final Receiver receiver;

		private final int[] channelVolumes = new int[16];

		private int volume;

		private VolumeReceiver(Receiver receiver, int volume) {
			this.receiver = receiver;
			this.volume = volume;
			for (int channel = 0; channel < this.channelVolumes.length; channel++) {
				this.channelVolumes[channel] = 127;
			}
			reapplyVolume();
			disableReverb();
		}

		@Override
		public void send(MidiMessage message, long timeStamp) {
			if (message instanceof ShortMessage) {
				ShortMessage shortMessage = (ShortMessage) message;
				int data1 = shortMessage.getData1();
				if (shortMessage.getCommand() == ShortMessage.CONTROL_CHANGE) {
					if (data1 == 7) {
						int channel = shortMessage.getChannel();
						this.channelVolumes[channel] = shortMessage.getData2();
						this.sendChannelVolume(channel, timeStamp);
						return;
					}
					if (data1 == 91 || data1 == 93) {
						return;
					}
				}
			}
			this.receiver.send(message, timeStamp);
		}

		@Override
		public void close() {
			this.receiver.close();
		}

		private void setVolume(int volume) {
			this.volume = volume;
			for (int channel = 0; channel < this.channelVolumes.length; channel++) {
				this.sendChannelVolume(channel, -1L);
			}
		}

		private void reapplyVolume() {
			for (int channel = 0; channel < this.channelVolumes.length; channel++) {
				this.sendChannelVolume(channel, -1L);
			}
		}

		private void disableReverb() {
			for (int channel = 0; channel < 16; channel++) {
				try {
					this.receiver.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 91, 0), -1L);
					this.receiver.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 93, 0), -1L);
				} catch (Exception ex) {
					System.err.println("Unable to disable reverb: " + ex.getMessage());
				}
			}
		}

		private void sendChannelVolume(int channel, long timeStamp) {
			int scaledVolume = (int) Math.round(this.channelVolumes[channel] * Math.pow(10.0D, this.volume / 1000.0D));
			try {
				this.receiver.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 7, scaledVolume), timeStamp);
			} catch (Exception ex) {
				System.err.println("Unable to adjust midi volume: " + ex.getMessage());
			}
		}
	}
}
