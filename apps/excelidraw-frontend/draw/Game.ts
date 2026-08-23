import { ShapeSchema, type Point, type Shape } from "@repo/common/types";
import { getExistingShapes } from "./http";

export type Tool = "circle" | "rect" | "pencil" | "hand" | "text";

const TEXT_FONT_SIZE = 20;

/** Emitted when the user clicks with the text tool selected, so the React
 * layer can show an editable overlay positioned over the click point. */
export interface TextEditRequest {
  screenX: number;
  screenY: number;
  world: Point;
}

const MIN_SCALE = 0.1;
const MAX_SCALE = 8;
const STROKE = "#ffffff";
const BACKGROUND = "#0f0f0f";

/** Viewport state shared with the React toolbar. */
export interface Camera {
  offsetX: number;
  offsetY: number;
  scale: number;
}

export class Game {
  private canvas: HTMLCanvasElement;
  private ctx: CanvasRenderingContext2D;
  private roomId: string;
  private socket: WebSocket;

  private existingShapes: Shape[] = [];
  private selectedTool: Tool = "pencil";

  /** Set while the pointer is down and a shape is being drawn. */
  private drawing = false;
  private startWorld: Point = { x: 0, y: 0 };
  private currentWorld: Point = { x: 0, y: 0 };
  private pencilPoints: Point[] = [];

  /** Set while the canvas is being panned. */
  private panning = false;
  private panStartScreen: Point = { x: 0, y: 0 };
  private panStartOffset: Point = { x: 0, y: 0 };
  private spaceHeld = false;

  private camera: Camera = { offsetX: 0, offsetY: 0, scale: 1 };
  private onCameraChange?: (camera: Camera) => void;
  private onTextEditRequest?: (request: TextEditRequest) => void;

  private resizeObserver?: ResizeObserver;
  private renderHandle: number | null = null;
  private destroyed = false;

  constructor(canvas: HTMLCanvasElement, roomId: string, socket: WebSocket) {
    this.canvas = canvas;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      throw new Error("2D canvas context is not available in this browser");
    }
    this.ctx = ctx;
    this.roomId = roomId;
    this.socket = socket;

    this.resize();
    this.initHandlers();
    this.initMouseHandlers();
    void this.init();
  }

  /* ------------------------------- public API ------------------------------ */

  setTool(tool: Tool) {
    this.selectedTool = tool;
    this.updateCursor();
  }

  onCamera(listener: (camera: Camera) => void) {
    this.onCameraChange = listener;
    listener({ ...this.camera });
  }

  onTextEdit(listener: (request: TextEditRequest) => void) {
    this.onTextEditRequest = listener;
  }

  /** Commits typed text as a shape. Called by the React overlay on submit. */
  commitText(world: Point, content: string) {
    const trimmed = content.trim();
    if (!trimmed) return;

    const shape: Shape = {
      type: "text",
      x: world.x,
      y: world.y,
      content: trimmed,
      fontSize: TEXT_FONT_SIZE,
    };

    this.existingShapes.push(shape);
    this.scheduleRender();
    this.publish(shape);
  }

  zoomBy(factor: number) {
    const rect = this.canvas.getBoundingClientRect();
    this.applyZoom(factor, { x: rect.width / 2, y: rect.height / 2 });
  }

  resetView() {
    this.camera = { offsetX: 0, offsetY: 0, scale: 1 };
    this.emitCamera();
    this.scheduleRender();
  }

  destroy() {
    this.destroyed = true;

    this.canvas.removeEventListener("pointerdown", this.pointerDownHandler);
    this.canvas.removeEventListener("pointermove", this.pointerMoveHandler);
    this.canvas.removeEventListener("pointerup", this.pointerUpHandler);
    this.canvas.removeEventListener("pointercancel", this.pointerUpHandler);
    this.canvas.removeEventListener("pointerleave", this.pointerUpHandler);
    this.canvas.removeEventListener("wheel", this.wheelHandler);
    this.canvas.removeEventListener("contextmenu", this.contextMenuHandler);
    window.removeEventListener("keydown", this.keyDownHandler);
    window.removeEventListener("keyup", this.keyUpHandler);

    this.resizeObserver?.disconnect();

    if (this.renderHandle !== null) {
      cancelAnimationFrame(this.renderHandle);
      this.renderHandle = null;
    }

    // Do not close the socket here - it is owned by the React component.
    if (this.socket.onmessage) {
      this.socket.onmessage = null;
    }
  }

  /* --------------------------------- setup --------------------------------- */

  private async init() {
    try {
      this.existingShapes = await getExistingShapes(this.roomId);
    } catch (e) {
      console.error("[canvas] could not load existing shapes:", e);
      this.existingShapes = [];
    }
    if (!this.destroyed) {
      this.scheduleRender();
    }
  }

  private initHandlers() {
    this.socket.onmessage = (event) => {
      let payload: { type?: string; message?: string };
      try {
        payload = JSON.parse(event.data);
      } catch {
        return;
      }

      if (payload.type !== "chat" || !payload.message) return;

      try {
        const parsed = JSON.parse(payload.message);
        const shape = ShapeSchema.safeParse(parsed?.shape);
        if (!shape.success) return;

        this.existingShapes.push(shape.data);
        this.scheduleRender();
      } catch {
        // Ignore malformed broadcasts rather than breaking the canvas.
      }
    };
  }

  private initMouseHandlers() {
    this.canvas.addEventListener("pointerdown", this.pointerDownHandler);
    this.canvas.addEventListener("pointermove", this.pointerMoveHandler);
    this.canvas.addEventListener("pointerup", this.pointerUpHandler);
    this.canvas.addEventListener("pointercancel", this.pointerUpHandler);
    this.canvas.addEventListener("pointerleave", this.pointerUpHandler);
    this.canvas.addEventListener("wheel", this.wheelHandler, { passive: false });
    this.canvas.addEventListener("contextmenu", this.contextMenuHandler);
    window.addEventListener("keydown", this.keyDownHandler);
    window.addEventListener("keyup", this.keyUpHandler);

    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(this.canvas);

    this.updateCursor();
  }

  /** Sizes the backing store to the CSS size times the device pixel ratio. */
  private resize() {
    const rect = this.canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    const width = Math.max(1, Math.round(rect.width * dpr));
    const height = Math.max(1, Math.round(rect.height * dpr));

    if (this.canvas.width !== width || this.canvas.height !== height) {
      this.canvas.width = width;
      this.canvas.height = height;
    }
    this.scheduleRender();
  }

  /* ------------------------------- coordinates ------------------------------ */

  private toWorld(e: PointerEvent): Point {
    const rect = this.canvas.getBoundingClientRect();
    const screenX = e.clientX - rect.left;
    const screenY = e.clientY - rect.top;
    return {
      x: (screenX - this.camera.offsetX) / this.camera.scale,
      y: (screenY - this.camera.offsetY) / this.camera.scale,
    };
  }

  private toScreen(e: PointerEvent): Point {
    const rect = this.canvas.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  }

  private applyZoom(factor: number, anchorScreen: Point) {
    const previous = this.camera.scale;
    const next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, previous * factor));
    if (next === previous) return;

    // Keep the world point under the anchor fixed while zooming.
    this.camera.offsetX =
      anchorScreen.x - ((anchorScreen.x - this.camera.offsetX) * next) / previous;
    this.camera.offsetY =
      anchorScreen.y - ((anchorScreen.y - this.camera.offsetY) * next) / previous;
    this.camera.scale = next;

    this.emitCamera();
    this.scheduleRender();
  }

  private emitCamera() {
    this.onCameraChange?.({ ...this.camera });
  }

  private updateCursor() {
    const panMode = this.selectedTool === "hand" || this.spaceHeld;
    this.canvas.style.cursor = panMode
      ? this.panning
        ? "grabbing"
        : "grab"
      : "crosshair";
  }

  /* --------------------------------- input --------------------------------- */

  private pointerDownHandler = (e: PointerEvent) => {
    // Middle mouse or space always pans, regardless of the selected tool.
    const wantsPan =
      this.selectedTool === "hand" || this.spaceHeld || e.button === 1;

    if (wantsPan) {
      this.canvas.setPointerCapture(e.pointerId);
      this.panning = true;
      this.panStartScreen = this.toScreen(e);
      this.panStartOffset = { x: this.camera.offsetX, y: this.camera.offsetY };
      this.updateCursor();
      return;
    }

    if (e.button !== 0) return;

    if (this.selectedTool === "text") {
      // Prevent the browser's default mousedown focus handling, which would
      // otherwise steal focus back from the text overlay right after it mounts.
      e.preventDefault();
      const screen = this.toScreen(e);
      this.onTextEditRequest?.({
        screenX: screen.x,
        screenY: screen.y,
        world: this.toWorld(e),
      });
      return;
    }

    this.canvas.setPointerCapture(e.pointerId);

    this.drawing = true;
    this.startWorld = this.toWorld(e);
    this.currentWorld = this.startWorld;
    this.pencilPoints = this.selectedTool === "pencil" ? [this.startWorld] : [];
  };

  private pointerMoveHandler = (e: PointerEvent) => {
    if (this.panning) {
      const screen = this.toScreen(e);
      this.camera.offsetX = this.panStartOffset.x + (screen.x - this.panStartScreen.x);
      this.camera.offsetY = this.panStartOffset.y + (screen.y - this.panStartScreen.y);
      this.emitCamera();
      this.scheduleRender();
      return;
    }

    if (!this.drawing) return;

    this.currentWorld = this.toWorld(e);

    if (this.selectedTool === "pencil") {
      const last = this.pencilPoints[this.pencilPoints.length - 1];
      // Skip points that are visually identical to keep payloads small.
      if (!last || Math.hypot(this.currentWorld.x - last.x, this.currentWorld.y - last.y) > 1) {
        this.pencilPoints.push(this.currentWorld);
      }
    }

    this.scheduleRender();
  };

  private pointerUpHandler = (e: PointerEvent) => {
    if (this.canvas.hasPointerCapture(e.pointerId)) {
      this.canvas.releasePointerCapture(e.pointerId);
    }

    if (this.panning) {
      this.panning = false;
      this.updateCursor();
      return;
    }

    if (!this.drawing) return;
    this.drawing = false;

    const shape = this.buildShape();
    this.pencilPoints = [];

    if (!shape) {
      this.scheduleRender();
      return;
    }

    this.existingShapes.push(shape);
    this.scheduleRender();
    this.publish(shape);
  };

  private wheelHandler = (e: WheelEvent) => {
    e.preventDefault();

    const rect = this.canvas.getBoundingClientRect();
    const anchor = { x: e.clientX - rect.left, y: e.clientY - rect.top };

    // Ctrl/Cmd + wheel (and pinch on trackpads) zooms, plain wheel scrolls.
    if (e.ctrlKey || e.metaKey) {
      this.applyZoom(Math.exp(-e.deltaY * 0.01), anchor);
      return;
    }

    this.camera.offsetX -= e.deltaX;
    this.camera.offsetY -= e.deltaY;
    this.emitCamera();
    this.scheduleRender();
  };

  private contextMenuHandler = (e: Event) => {
    e.preventDefault();
  };

  private keyDownHandler = (e: KeyboardEvent) => {
    if (e.code === "Space" && !this.spaceHeld) {
      // Do not hijack the spacebar while the user is typing somewhere else.
      const target = e.target as HTMLElement | null;
      if (target && /^(INPUT|TEXTAREA)$/.test(target.tagName)) return;

      this.spaceHeld = true;
      this.updateCursor();
      e.preventDefault();
    }
  };

  private keyUpHandler = (e: KeyboardEvent) => {
    if (e.code === "Space") {
      this.spaceHeld = false;
      this.updateCursor();
    }
  };

  /* --------------------------------- shapes -------------------------------- */

  private buildShape(): Shape | null {
    const width = this.currentWorld.x - this.startWorld.x;
    const height = this.currentWorld.y - this.startWorld.y;

    if (this.selectedTool === "rect") {
      if (Math.abs(width) < 1 && Math.abs(height) < 1) return null;
      // Normalise so dragging up/left still produces a positive-size rect.
      return {
        type: "rect",
        x: Math.min(this.startWorld.x, this.currentWorld.x),
        y: Math.min(this.startWorld.y, this.currentWorld.y),
        width: Math.abs(width),
        height: Math.abs(height),
      };
    }

    if (this.selectedTool === "circle") {
      const radius = Math.max(Math.abs(width), Math.abs(height)) / 2;
      if (radius < 1) return null;
      return {
        type: "circle",
        centerX: this.startWorld.x + width / 2,
        centerY: this.startWorld.y + height / 2,
        radius,
      };
    }

    if (this.selectedTool === "pencil" && this.pencilPoints.length > 1) {
      return { type: "pencil", points: this.pencilPoints.slice(0, 5000) };
    }

    return null;
  }

  private publish(shape: Shape) {
    if (this.socket.readyState !== WebSocket.OPEN) return;

    this.socket.send(
      JSON.stringify({
        type: "chat",
        roomId: this.roomId,
        message: JSON.stringify({ shape }),
      }),
    );
  }

  /* -------------------------------- rendering ------------------------------- */

  private scheduleRender() {
    if (this.destroyed || this.renderHandle !== null) return;
    this.renderHandle = requestAnimationFrame(() => {
      this.renderHandle = null;
      this.render();
    });
  }

  private render() {
    const dpr = window.devicePixelRatio || 1;
    const { offsetX, offsetY, scale } = this.camera;

    this.ctx.setTransform(1, 0, 0, 1, 0, 0);
    this.ctx.fillStyle = BACKGROUND;
    this.ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);

    this.ctx.setTransform(
      scale * dpr,
      0,
      0,
      scale * dpr,
      offsetX * dpr,
      offsetY * dpr,
    );

    this.ctx.strokeStyle = STROKE;
    this.ctx.lineWidth = 2 / scale;
    this.ctx.lineCap = "round";
    this.ctx.lineJoin = "round";

    for (const shape of this.existingShapes) {
      this.drawShape(shape);
    }

    // Live preview of the shape currently being drawn.
    if (this.drawing) {
      const preview = this.buildPreviewShape();
      if (preview) this.drawShape(preview);
    }
  }

  private buildPreviewShape(): Shape | null {
    if (this.selectedTool === "pencil") {
      return this.pencilPoints.length > 1
        ? { type: "pencil", points: this.pencilPoints }
        : null;
    }

    const width = this.currentWorld.x - this.startWorld.x;
    const height = this.currentWorld.y - this.startWorld.y;

    if (this.selectedTool === "rect") {
      return {
        type: "rect",
        x: Math.min(this.startWorld.x, this.currentWorld.x),
        y: Math.min(this.startWorld.y, this.currentWorld.y),
        width: Math.abs(width),
        height: Math.abs(height),
      };
    }

    if (this.selectedTool === "circle") {
      return {
        type: "circle",
        centerX: this.startWorld.x + width / 2,
        centerY: this.startWorld.y + height / 2,
        radius: Math.max(Math.abs(width), Math.abs(height)) / 2,
      };
    }

    return null;
  }

  private drawShape(shape: Shape) {
    if (shape.type === "rect") {
      this.ctx.strokeRect(shape.x, shape.y, shape.width, shape.height);
      return;
    }

    if (shape.type === "circle") {
      this.ctx.beginPath();
      this.ctx.arc(shape.centerX, shape.centerY, Math.abs(shape.radius), 0, Math.PI * 2);
      this.ctx.stroke();
      this.ctx.closePath();
      return;
    }

    if (shape.type === "pencil") {
      const [first, ...rest] = shape.points;
      if (!first) return;

      this.ctx.beginPath();
      this.ctx.moveTo(first.x, first.y);
      for (const point of rest) {
        this.ctx.lineTo(point.x, point.y);
      }
      this.ctx.stroke();
      return;
    }

    if (shape.type === "text") {
      this.ctx.font = `${shape.fontSize}px sans-serif`;
      this.ctx.fillStyle = STROKE;
      this.ctx.textBaseline = "top";
      this.ctx.fillText(shape.content, shape.x, shape.y);
    }
  }
}
