package com.android.glmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.RejectedExecutionException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.AsyncTask;
import android.util.Log;

public class GLMapRenderer implements GLSurfaceView.Renderer {
	private final String TAG = "GLMapRenderer";
	private final boolean debug = false;

	private final float START_X = 980073.56f;
	private final float START_Y = 6996566.0f;
	private final int START_Z = 11; // zoom level
	private final int TILE_SIZE = 500;

	private final int NROF_TILES_X = 8;
	private final int NROF_TILES_Y = 8;
	private final int NROF_TILES = NROF_TILES_X * NROF_TILES_Y;

	private final int POLYGON_VERTICES_DATA_POS_OFFSET = 0;
	private final int LINE_VERTICES_DATA_POS_OFFSET = 0;
	private final int LINE_VERTICES_DATA_TEX_OFFSET = 12;
	private final int LINE_VERTICES_DATA_COLOR1_OFFSET = 0;
	private final int LINE_VERTICES_DATA_COLOR2_OFFSET = 4;
	private final int POLY_VERTEX_SIZE = 8;

	private GLMapView mapView;
	private GLMapTile[][] tiles;
	private boolean initialized;
	private GLMapLoader glMapLoader;
	private FloatBuffer fullscreenCoordsBuffer;

	private int gLineProgram;
	private int gLinevPositionHandle;
	private int gLinetexPositionHandle;
	private int gLineColorHandle;
	private int gLinecPositionHandle;
	private int gLineWidthHandle;
	private int gLineHeightOffsetHandle;
	private int gLineScaleXHandle;
	private int gLineScaleYHandle;
	private int gPolygonProgram;
	private int gPolygonvPositionHandle;
	private int gPolygoncPositionHandle;
	private int gPolygonScaleXHandle;
	private int gPolygonScaleYHandle;
	private int gPolygonFillProgram;
	private int gPolygonFillvPositionHandle;
	private int gPolygonFillColorHandle;

	private float xPos = START_X;
	private float yPos = START_Y;
	private float zPos = (float) (1.0 / Math.pow(2, START_Z));
	private int width, height;

	private long lastDraw = 0;
	private boolean gles_shader = true;

	public GLMapRenderer(GLMapView mapview) {
		this.mapView = mapview;
		this.glMapLoader = new GLMapLoader();
	}

	private void init() {
		// Set up the program for rendering lines
		gLineProgram = Utils.createProgram(Shaders.gLineVertexShader,
		                                   Shaders.gLineFragmentShader);
		if (gLineProgram == 0) {
			gles_shader = false;
			Log.e(TAG, "Could not create program.");
			gLineProgram = Utils.createProgram(Shaders.gLineVertexShaderSimple,
			                                   Shaders.gLineFragmentShaderSimple);
			if (gLineProgram == 0) {
				Log.e(TAG, "Could not create program.");
				return;
			}
		}

		gLinecPositionHandle = GLES20.glGetUniformLocation(gLineProgram, "u_center");
		gLineScaleXHandle = GLES20.glGetUniformLocation(gLineProgram, "scaleX");
		gLineScaleYHandle = GLES20.glGetUniformLocation(gLineProgram, "scaleY");
		gLineHeightOffsetHandle = GLES20.glGetUniformLocation(gLineProgram, "height_offset");
		gLineWidthHandle = GLES20.glGetUniformLocation(gLineProgram, "width");
		gLinevPositionHandle = GLES20.glGetAttribLocation(gLineProgram, "a_position");
		gLinetexPositionHandle = GLES20.glGetAttribLocation(gLineProgram, "a_st");
		gLineColorHandle = GLES20.glGetAttribLocation(gLineProgram, "a_color");
		Utils.checkGlError("glGetAttribLocation");

		// Set up the program for rendering polygons
		gPolygonProgram = Utils.createProgram(Shaders.gPolygonVertexShader,
		                                      Shaders.gPolygonFragmentShader);
		if (gPolygonProgram == 0) {
			Log.e(TAG, "Could not create program.");
			return;
		}
		gPolygoncPositionHandle = GLES20.glGetUniformLocation(gPolygonProgram, "u_center");
		gPolygonScaleXHandle = GLES20.glGetUniformLocation(gPolygonProgram, "scaleX");
		gPolygonScaleYHandle = GLES20.glGetUniformLocation(gPolygonProgram, "scaleY");
		gPolygonvPositionHandle = GLES20.glGetAttribLocation(gPolygonProgram, "a_position");
		Utils.checkGlError("glGetUniformLocation");

		// Set up the program for filling polygons
		gPolygonFillProgram = Utils.createProgram(Shaders.gPolygonFillVertexShader,
		                                          Shaders.gPolygonFillFragmentShader);
		if (gPolygonFillProgram == 0) {
			Log.e(TAG, "Could not create program.");
			return;
		}
		gPolygonFillvPositionHandle = GLES20.glGetAttribLocation(gPolygonFillProgram,
		                                                         "a_position");
		gPolygonFillColorHandle = GLES20.glGetUniformLocation(gPolygonFillProgram, "u_color");
		Utils.checkGlError("glGetUniformLocation");

		// Set up vertex buffer objects
		int[] vboIds = new int[3 * NROF_TILES];
		GLES20.glGenBuffers(3 * NROF_TILES, vboIds, 0);

		// Set up the tile handles
		tiles = new GLMapTile[NROF_TILES_X][];
		GLMapTile tile;
		for (int i = 0; i < NROF_TILES_X; i++) {
			tiles[i] = new GLMapTile[NROF_TILES_Y];
			for (int j = 0; j < NROF_TILES_Y; j++) {
				tile = new GLMapTile();
				int n = i + j * NROF_TILES_X;
				tile.lineVBO = vboIds[3 * n];
				tile.colorVBO = vboIds[3 * n + 1];
				tile.polygonVBO = vboIds[3 * n + 2];
				tile.nrofLineVertices = 0;
				tile.nrofPolygonVertices = 0;
				tile.x = -1;
				tile.y = -1;
				tile.newData = false;
				tiles[i][j] = tile;
			}
		}

		float[] coords = { -1.0f, 1.0f, 1.0f, 1.0f, -1.0f, -1.0f, 1.0f, -1.0f };

		fullscreenCoordsBuffer = ByteBuffer.allocateDirect(8 * 4)
		      .order(ByteOrder.nativeOrder())
		      .asFloatBuffer().put(coords);

		// Set general settings
		GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glDepthFunc(GLES20.GL_LEQUAL);

		GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
		GLES20.glDisable(GLES20.GL_STENCIL_TEST);

		GLES20.glCullFace(GLES20.GL_BACK);
		GLES20.glFrontFace(GLES20.GL_CW);

		Log.i(TAG, "Initialization complete.");
		this.initialized = true;
	}

	public void onDrawFrame(GL10 gl) {
		if (debug)
			lastDraw = System.currentTimeMillis();

		mapRenderFrame();

		if (debug)
			Log.i(TAG, "draw took: " + (System.currentTimeMillis() - lastDraw));
	}

	public void onSurfaceChanged(GL10 glUnused, int w, int h) {
		this.width = w;
		this.height = h;

		GLES20.glViewport(0, 0, w, h);
		Utils.checkGlError("GLES20.glViewport");

		mapMove(this.xPos, this.yPos, this.zPos, true);
	}

	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
		init();
	}

	public void move(float x, float y) {
		this.xPos = this.xPos - x / ((this.zPos / 2) * this.width);
		this.yPos = this.yPos - y / ((this.zPos / 2) * this.height);

		mapMove(this.xPos, this.yPos, this.zPos, false);
	}

	public void zoom(float z) {
		this.zPos = this.zPos * z;

		mapMove(this.xPos, this.yPos, this.zPos, false);
	}

	synchronized int mapMove(float x, float y, float z, boolean sync) {
		if (!this.initialized)
			return 0;

		int xx = (int) ((x - 0.5 * TILE_SIZE * NROF_TILES_X) / TILE_SIZE);
		int yy = (int) ((y - 0.5 * TILE_SIZE * NROF_TILES_Y) / TILE_SIZE);

		// Check if any new tiles need to be loaded
		for (int i = 0; i < NROF_TILES_X; i++) {
			for (int j = 0; j < NROF_TILES_Y; j++) {
				int tx = xx + i;
				int ty = yy + j;

				int s = tx % NROF_TILES_X;
				int t = ty % NROF_TILES_Y;

				if (tiles[s][t].loading)
					continue;

				if ((tiles[s][t].x == tx) && (tiles[s][t].y == ty))
					continue;

				tiles[s][t].x = tx;
				tiles[s][t].y = ty;

				String tilename = tx + "_" + ty;

				if (sync) {
					if (glMapLoader.loadMapTile(tilename, tiles[s][t]))
						tiles[s][t].newData = true;
				} else {
					loadTile(tilename, tiles[s][t], this);
				}
			}
		}

		this.mapView.requestRender();

		return 0;
	}

	private void loadTile(final String name, final GLMapTile tile, final GLMapRenderer renderer) {
		if (tile.loading)
			tile.task.cancel(true);

		tile.loading = true;
		tile.newData = false;

		tile.task = new AsyncTask<Void, Void, Integer>() {
			@Override
			protected void onPreExecute() {
			}

			@Override
			protected Integer doInBackground(Void... args0) {
				if (glMapLoader.loadMapTile(name, tile)) {
					tile.newData = true;
					tile.loading = false;
					renderer.mapView.requestRender();
				}
				tile.loading = false;
				return null;
			}
		};

		try {
			tile.task.execute();
		} catch (RejectedExecutionException e) {
			tile.task.cancel(true);
		}
	}

	private synchronized void mapRenderFrame() {
		float x = this.xPos;
		float y = this.yPos;
		float z = zPos;

		// Check if any new tiles need to be loaded into graphics memory
		for (int i = 0; i < NROF_TILES_X; i++) {
			for (int j = 0; j < NROF_TILES_Y; j++) {
				if (!tiles[i][j].newData)
					continue;

				if (tiles[i][j].nrofLineVertices > 0) {
					// Upload line data to graphics core vertex buffer object
					GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tiles[i][j].lineVBO);
					GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
					                    tiles[i][j].nrofLineVertices * 20,
					                    tiles[i][j].lineVerticesBuffer,
					                    GLES20.GL_DYNAMIC_DRAW);
					Utils.checkGlError("glBufferData1 " + +tiles[i][j].nrofLineVertices + " ");
					tiles[i][j].lineVerticesBuffer = null;

					GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tiles[i][j].colorVBO);
					GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
					                    tiles[i][j].nrofLineVertices * 8,
					                    tiles[i][j].colorVerticesBuffer,
					                    GLES20.GL_DYNAMIC_DRAW);
					tiles[i][j].colorVerticesBuffer = null;
				}
				// Upload polygon data to graphics core vertex buffer object
				if (tiles[i][j].nrofPolygonVertices > 0) {
					GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tiles[i][j].polygonVBO);
					GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
					                    tiles[i][j].nrofPolygonVertices * POLY_VERTEX_SIZE,
					                    tiles[i][j].polygonVerticesBuffer,
					                    GLES20.GL_DYNAMIC_DRAW);
					Utils.checkGlError("glBufferData2 " + +tiles[i][j].nrofPolygonVertices + " ");
					tiles[i][j].polygonVerticesBuffer = null;

				}
				tiles[i][j].newData = false;
			}
		}

		// Clear the buffers
		GLES20.glClearColor(244 / 255f, 244 / 255f, 240 / 255f, 1.0f);
		GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

		int cnt = 0;
		byte[] colors = new byte[20];

		// draw all tiles polygons of one layer into one stencil buffer
		// avoiding stencil buffer clears.
		for (int i = 0; i < NROF_TILES_X; i++) {
			for (int j = 0; j < NROF_TILES_Y; j++) {
				GLMapTile tile = tiles[i][j];
				if (tile.loading || tile.newData)
					continue;

				if (tile.polygonLayers == null)
					continue;

				for (PolygonLayer layer : tile.polygonLayers) {
					boolean found = false;
					byte color = layer.rgba[0];
					for (int c = 0; c < cnt; c++)
						if (colors[c] == color)
							found = true;

					if (!found)
						colors[cnt++] = color;
				}
			}
		}

		// Draw polygons into stencil buffer to find covered areas
		// This uses the method described here:
		// http://www.glprogramming.com/red/chapter14.html#name13
		GLES20.glEnable(GLES20.GL_STENCIL_TEST);

		for (int c = 0; c < cnt; c++) {
			GLES20.glDisable(GLES20.GL_DEPTH_TEST);
			GLES20.glDisable(GLES20.GL_CULL_FACE);
			GLES20.glDisable(GLES20.GL_BLEND);
			GLES20.glClear(GLES20.GL_STENCIL_BUFFER_BIT);

			GLES20.glStencilMask(0x01);
			GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_INVERT);
			GLES20.glStencilFunc(GLES20.GL_ALWAYS, 0, ~0);

			GLES20.glColorMask(false, false, false, false);
			GLES20.glDepthMask(false);

			GLES20.glUseProgram(gPolygonProgram);
			GLES20.glUniform4f(gPolygoncPositionHandle, x, y, 0.0f, 0.0f);
			GLES20.glUniform1f(gPolygonScaleXHandle, z * (float) (this.height)
			      / (float) (this.width));
			GLES20.glUniform1f(gPolygonScaleYHandle, z);

			PolygonLayer drawn = null;

			for (int i = 0; i < NROF_TILES_X; i++) {
				for (int j = 0; j < NROF_TILES_Y; j++) {
					GLMapTile tile = tiles[i][j];
					if (tile.loading || tile.newData)
						continue;

					if (tile.polygonLayers == null)
						continue;

					PolygonLayer layer = null;

					for (PolygonLayer l : tile.polygonLayers) {

						if (l.rgba[0] == colors[c]) {
							layer = l;
							break;
						}
					}

					if (layer == null)
						continue;

					GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tile.polygonVBO);

					GLES20.glVertexAttribPointer(gPolygonvPositionHandle, 2, GLES20.GL_FLOAT,
					                             false, 0, POLYGON_VERTICES_DATA_POS_OFFSET);

					GLES20.glEnableVertexAttribArray(gPolygonvPositionHandle);

					GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN,
					                    layer.startVertex,
					                    layer.nrofVertices);

					GLES20.glDisableVertexAttribArray(gPolygonvPositionHandle);

					drawn = layer;
				}
			}

			if (drawn != null) {
				GLES20.glColorMask(true, true, true, true);
				GLES20.glDepthMask(true);

				// Draw with the color to fill them
				GLES20.glUseProgram(gPolygonFillProgram);
				GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
				GLES20.glUniform4f(gPolygonFillColorHandle,
				                   1 + drawn.rgba[0] / 255.0f,
				                   1 + drawn.rgba[1] / 255.0f,
				                   1 + drawn.rgba[2] / 255.0f, 1);

				fullscreenCoordsBuffer.position(0);
				GLES20.glVertexAttribPointer(gPolygonFillvPositionHandle,
				                             2, GLES20.GL_FLOAT, false, 0,
				                             fullscreenCoordsBuffer);

				GLES20.glEnableVertexAttribArray(gPolygonFillvPositionHandle);

				GLES20.glStencilFunc(GLES20.GL_EQUAL, 1, 1);
				GLES20.glStencilOp(GLES20.GL_ZERO, GLES20.GL_ZERO,
				                   GLES20.GL_ZERO);

				GLES20.glEnable(GLES20.GL_DEPTH_TEST);
				GLES20.glEnable(GLES20.GL_CULL_FACE);
				GLES20.glEnable(GLES20.GL_BLEND);

				GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

				GLES20.glDisableVertexAttribArray(gPolygonFillvPositionHandle);
			}
		}

		GLES20.glDisable(GLES20.GL_STENCIL_TEST);
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glEnable(GLES20.GL_CULL_FACE);

		// Draw lines
		GLES20.glUseProgram(gLineProgram);
		GLES20.glUniform4f(gLinecPositionHandle, x, y, 0.0f, 0.0f);
		GLES20.glUniform1f(gLineScaleXHandle, z * (float) (this.height) / (float) (this.width));
		GLES20.glUniform1f(gLineScaleYHandle, z);

		for (int i = 0; i < NROF_TILES_X; i++) {
			for (int j = 0; j < NROF_TILES_Y; j++) {
				GLMapTile tile = tiles[i][j];
				if (tile.loading || tile.newData)
					continue;

				GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tile.lineVBO);
				GLES20.glEnableVertexAttribArray(gLinevPositionHandle);
				GLES20.glEnableVertexAttribArray(gLinetexPositionHandle);

				GLES20.glVertexAttribPointer(gLinevPositionHandle, 3, GLES20.GL_FLOAT, false,
				                             20, LINE_VERTICES_DATA_POS_OFFSET);

				GLES20.glVertexAttribPointer(gLinetexPositionHandle, 2, GLES20.GL_FLOAT, false,
				                             20, LINE_VERTICES_DATA_TEX_OFFSET);

				GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tile.colorVBO);
				GLES20.glEnableVertexAttribArray(gLineColorHandle);

				if (gles_shader) {
					// Draw outlines
					GLES20.glVertexAttribPointer(gLineColorHandle, 4, GLES20.GL_UNSIGNED_BYTE,
					                             true, 8, LINE_VERTICES_DATA_COLOR2_OFFSET);

					GLES20.glUniform1f(gLineWidthHandle, 1.0f);
					GLES20.glUniform1f(gLineHeightOffsetHandle, 0.1f);

					GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, tile.nrofLineVertices);
				}

				// Draw fill
				GLES20.glVertexAttribPointer(gLineColorHandle, 4, GLES20.GL_UNSIGNED_BYTE, true,
				                             8, LINE_VERTICES_DATA_COLOR1_OFFSET);
				if (gles_shader)
					GLES20.glUniform1f(gLineWidthHandle, 0.7f);

				GLES20.glUniform1f(gLineHeightOffsetHandle, 1.0f);

				GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, tile.nrofLineVertices);
			}
		}

		GLES20.glDisableVertexAttribArray(gLinetexPositionHandle);
		GLES20.glDisableVertexAttribArray(gLinevPositionHandle);
		GLES20.glDisableVertexAttribArray(gLineColorHandle);
	}
}
