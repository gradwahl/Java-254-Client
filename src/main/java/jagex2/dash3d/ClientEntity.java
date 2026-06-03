package jagex2.dash3d;

import deob.ObfuscatedName;
import jagex2.client.Client;
import jagex2.config.SeqType;

@ObfuscatedName("z")
public class ClientEntity extends ModelSource {

	@ObfuscatedName("z.o")
	public int x;

	@ObfuscatedName("z.p")
	public int z;

	/** Scene position at the start of the current logic tick, for 60fps render-time
	 *  position interpolation. Updated each tick in Client.moveEntity. */
	public int prevSceneX;
	public int prevSceneZ;

	@ObfuscatedName("z.q")
	public int yaw;

	@ObfuscatedName("z.r")
	public boolean needsForwardDrawPadding = false;

	@ObfuscatedName("z.s")
	public int size = 1;

	@ObfuscatedName("z.t")
	public int readyanim = -1;

	@ObfuscatedName("z.u")
	public int turnanim = -1;

	@ObfuscatedName("z.v")
	public int walkanim = -1;

	@ObfuscatedName("z.w")
	public int walkanim_b = -1;

	@ObfuscatedName("z.x")
	public int walkanim_l = -1;

	@ObfuscatedName("z.y")
	public int walkanim_r = -1;

	@ObfuscatedName("z.z")
	public int runanim = -1;

	@ObfuscatedName("z.ab")
	public int exactMoveStartSceneTileX;

	@ObfuscatedName("z.bb")
	public int exactMoveEndSceneTileX;

	@ObfuscatedName("z.cb")
	public int exactMoveStartSceneTileZ;

	@ObfuscatedName("z.db")
	public int exactMoveEndSceneTileZ;

	@ObfuscatedName("z.eb")
	public int exactMoveEndCycle;

	@ObfuscatedName("z.fb")
	public int exactMoveStartCycle;

	@ObfuscatedName("z.gb")
	public int exactMoveFaceDirection;

	@ObfuscatedName("z.hb")
	public int cycle;

	@ObfuscatedName("z.ib")
	public int height = 200;

	@ObfuscatedName("z.jb")
	public int dstYaw;

	@ObfuscatedName("z.kb")
	public int turnspeed = 32;

	@ObfuscatedName("z.lb")
	public int routeLength;

	@ObfuscatedName("z.mb")
	public int[] routeTileX = new int[10];

	@ObfuscatedName("z.nb")
	public int[] routeTileZ = new int[10];

	@ObfuscatedName("z.ob")
	public boolean[] routeRun = new boolean[10];

	@ObfuscatedName("z.pb")
	public int seqDelayMove;

	@ObfuscatedName("z.qb")
	public int preanimRouteLength;

	@ObfuscatedName("z.B")
	public int chatTimer = 100;

	@ObfuscatedName("z.E")
	public int[] damage = new int[4];

	@ObfuscatedName("z.F")
	public int[] damageType = new int[4];

	@ObfuscatedName("z.G")
	public int[] damageCycle = new int[4];

	@ObfuscatedName("z.H")
	public int combatCycle = -1000;

	@ObfuscatedName("z.K")
	public int targetId = -1;

	@ObfuscatedName("z.N")
	public int secondarySeqId = -1;

	@ObfuscatedName("z.Q")
	public int primarySeqId = -1;

	@ObfuscatedName("z.V")
	public int spotanimId = -1;

	@ObfuscatedName("z.C")
	public int chatColour;

	@ObfuscatedName("z.D")
	public int chatEffect;

	@ObfuscatedName("z.I")
	public int health;

	@ObfuscatedName("z.J")
	public int totalHealth;

	@ObfuscatedName("z.L")
	public int targetTileX;

	@ObfuscatedName("z.M")
	public int targetTileZ;

	@ObfuscatedName("z.O")
	public int secondarySeqFrame;

	@ObfuscatedName("z.P")
	public int secondarySeqCycle;

	@ObfuscatedName("z.R")
	public int primarySeqFrame;

	@ObfuscatedName("z.S")
	public int primarySeqCycle;

	@ObfuscatedName("z.T")
	public int primarySeqDelay;

	@ObfuscatedName("z.U")
	public int primarySeqLoop;

	@ObfuscatedName("z.W")
	public int spotanimFrame;

	@ObfuscatedName("z.X")
	public int spotanimCycle;

	@ObfuscatedName("z.Y")
	public int spotanimLastCycle;

	@ObfuscatedName("z.Z")
	public int spotanimHeight;

	@ObfuscatedName("z.A")
	public String chatMessage;

	// ---- Render-time animation interpolation (60fps "smooth" mode) ----
	/** Sub-tick fraction in [0,1] through the current 50fps logic tick. */
	public static float renderInterp = 0f;
	/** Whether animation interpolation is active this frame. */
	public static boolean renderInterpOn = false;
	/** Scratch output of {@link #seqInterpWeight}: the AnimFrame id to blend FROM. */
	public int interpFromFrame = -1;
	// Lag-interpolation state: the keyframe currently displayed and the one shown
	// just before it, tracked separately for the primary and secondary sequences.
	private int interpObservedPrimary = -1;
	private int interpPrevPrimary = -1;
	private int interpObservedSecondary = -1;
	private int interpPrevSecondary = -1;

	/**
	 * Lag interpolation: blends FROM the keyframe shown just before the current
	 * one TO the current keyframe, across the current frame's hold window. Returns
	 * the blend weight (0..256) and sets {@link #interpFromFrame} to the frame to
	 * blend from; the caller renders {@code animateInterpolated(interpFromFrame,
	 * currentFrame, weight)}.
	 *
	 * <p>Because it always blends between two frames that were actually displayed,
	 * it handles every transition the same way — normal advances, loop wraps, and
	 * even server-driven restarts (which jump the frame backwards) — with no
	 * special cases. Returns 0 with interpFromFrame = -1 when there is nothing to
	 * interpolate (interpolation off, or no distinct previous frame yet), so the
	 * caller falls back to a plain single frame.
	 */
	public int seqInterpWeight(SeqType seq, int frameIndex, int cycle, boolean secondary) {
		this.interpFromFrame = -1;
		if (seq == null || frameIndex < 0 || frameIndex >= seq.numFrames) {
			return 0;
		}
		int curFrame = seq.frames[frameIndex];
		// Track the previous distinct keyframe. Kept up to date every render (even
		// when interpolation is off) so it's correct the instant it's re-enabled.
		int prev;
		if (secondary) {
			if (curFrame != this.interpObservedSecondary) {
				this.interpPrevSecondary = this.interpObservedSecondary;
				this.interpObservedSecondary = curFrame;
			}
			prev = this.interpPrevSecondary;
		} else {
			if (curFrame != this.interpObservedPrimary) {
				this.interpPrevPrimary = this.interpObservedPrimary;
				this.interpObservedPrimary = curFrame;
			}
			prev = this.interpPrevPrimary;
		}
		if (!renderInterpOn || renderInterp < 0f || prev == -1 || prev == curFrame) {
			return 0;
		}
		int duration = seq.getDuration(frameIndex);
		if (duration <= 0) {
			return 0;
		}
		// Continuous position through the current frame's hold window, in [0,1].
		// Secondary cycles run 0..duration (duration+1 windows); primary cycles run
		// 1..duration. At pos 0 the previous frame is shown, at pos 1 the current.
		float pos = secondary
				? (cycle + renderInterp) / (float) (duration + 1)
				: (cycle - 1 + renderInterp) / (float) duration;
		if (pos < 0f) {
			pos = 0f;
		}
		if (pos > 1f) {
			pos = 1f;
		}
		this.interpFromFrame = prev;
		return (int) (pos * 256f);
	}

	@ObfuscatedName("z.a(IIZZ)V")
	public void teleport(int arg0, int arg1, boolean arg3) {
		if (this.primarySeqId != -1 && SeqType.list[this.primarySeqId].postanim_move == 1) {
			this.primarySeqId = -1;
		}
		if (!arg3) {
			int var5 = arg1 - this.routeTileX[0];
			int var6 = arg0 - this.routeTileZ[0];
			if (var5 >= -8 && var5 <= 8 && var6 >= -8 && var6 <= 8) {
				if (this.routeLength < 9) {
					this.routeLength++;
				}
				for (int var7 = this.routeLength; var7 > 0; var7--) {
					this.routeTileX[var7] = this.routeTileX[var7 - 1];
					this.routeTileZ[var7] = this.routeTileZ[var7 - 1];
					this.routeRun[var7] = this.routeRun[var7 - 1];
				}
				this.routeTileX[0] = arg1;
				this.routeTileZ[0] = arg0;
				this.routeRun[0] = false;
				return;
			}
		}
		this.routeLength = 0;
		this.preanimRouteLength = 0;
		this.seqDelayMove = 0;
		this.routeTileX[0] = arg1;
		this.routeTileZ[0] = arg0;
		this.x = this.routeTileX[0] * 128 + this.size * 64;
		this.z = this.routeTileZ[0] * 128 + this.size * 64;
	}

	@ObfuscatedName("z.a(ZIB)V")
	public void moveCode(boolean arg0, int arg1) {
		int var4 = this.routeTileX[0];
		int var5 = this.routeTileZ[0];
		if (arg1 == 0) {
			var4--;
			var5++;
		}
		if (arg1 == 1) {
			var5++;
		}
		if (arg1 == 2) {
			var4++;
			var5++;
		}
		if (arg1 == 3) {
			var4--;
		}
		if (arg1 == 4) {
			var4++;
		}
		if (arg1 == 5) {
			var4--;
			var5--;
		}
		if (arg1 == 6) {
			var5--;
		}
		if (arg1 == 7) {
			var4++;
			var5--;
		}
		if (this.primarySeqId != -1 && SeqType.list[this.primarySeqId].postanim_move == 1) {
			this.primarySeqId = -1;
		}
		if (this.routeLength < 9) {
			this.routeLength++;
		}
		for (int var6 = this.routeLength; var6 > 0; var6--) {
			this.routeTileX[var6] = this.routeTileX[var6 - 1];
			this.routeTileZ[var6] = this.routeTileZ[var6 - 1];
			this.routeRun[var6] = this.routeRun[var6 - 1];
		}
		this.routeTileX[0] = var4;
		this.routeTileZ[0] = var5;
		this.routeRun[0] = arg0;
	}

	@ObfuscatedName("z.a(Z)V")
	public void abortRoute() {
		this.routeLength = 0;
		this.preanimRouteLength = 0;
	}

	@ObfuscatedName("z.b(I)Z")
	public boolean isReady() {
		return false;
	}

	@ObfuscatedName("z.a(III)V")
	public void addHitmark(int arg1, int arg2) {
		for (int var4 = 0; var4 < 4; var4++) {
			if (this.damageCycle[var4] <= Client.loopCycle) {
				this.damage[var4] = arg2;
				this.damageType[var4] = arg1;
				this.damageCycle[var4] = Client.loopCycle + 70;
				return;
			}
		}
	}
}
